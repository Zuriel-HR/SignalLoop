package io.signalloop.central.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signalloop.central.agents.AgentContracts.CaseInput;
import io.signalloop.central.agents.AgentContracts.Classification;
import io.signalloop.central.agents.AgentContracts.Signal;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiInterpreterAgent {
    private static final String INSTRUCTIONS = """
            Eres el Intérprete de SignalLoop. Clasifica incidentes usando solo las señales observadas y el catálogo proporcionado.
            No inventes hechos. Si la señal no cabe con evidencia suficiente en una categoría existente, devuelve category=unknown y escribe proposed_new_category.
            Detecta campos de atributos no reconocidos y propone su mapeo a known_fields en field_mappings. Si no existe un destino adecuado,
            registra un new_field_proposal. No alteres el payload original ni inventes valores.
            evidence_event_ids debe contener exclusivamente IDs de las señales recibidas. Devuelve únicamente el JSON requerido.
            """;
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "category":{"type":"string","enum":["hardware","network","software","reconciliation","fraud","unknown"]},
              "severity":{"type":"string","enum":["P1","P2","P3"]},
              "next_action":{"type":"string"},
              "proposed_new_category":{"type":"string"},
              "field_mappings":{"type":"array","items":{"type":"object","properties":{
                "source_field":{"type":"string"},"canonical_field":{"type":"string"},"reason":{"type":"string"}},
                "required":["source_field","canonical_field","reason"],"additionalProperties":false}},
              "new_field_proposals":{"type":"array","items":{"type":"object","properties":{
                "source_field":{"type":"string"},"suggested_field":{"type":"string"},"reason":{"type":"string"}},
                "required":["source_field","suggested_field","reason"],"additionalProperties":false}},
              "confidence":{"type":"number","minimum":0,"maximum":1},
              "evidence_event_ids":{"type":"array","items":{"type":"string"}}},
              "required":["category","severity","next_action","proposed_new_category","field_mappings","new_field_proposals","confidence","evidence_event_ids"],
              "additionalProperties":false}
            """;

    private final OpenAiResponsesClient client;
    private final ObjectMapper json;
    private final InterpreterAgent rules;
    @ConfigProperty(name = "signalloop.ai.interpreter-reasoning", defaultValue = "low") String reasoningEffort;

    public AiInterpreterAgent(OpenAiResponsesClient client, ObjectMapper json, InterpreterAgent rules) {
        this.client = client;
        this.json = json;
        this.rules = rules;
    }

    public boolean isConfigured() {
        return client.configured();
    }

    public Classification classify(CaseInput input, Map<String, Object> snapshot) {
        Classification deterministic = rules.classify(input, snapshot);
        String matchedRule = deterministic.matchedRule();
        if (matchedRule != null && !matchedRule.equals("unmatched") && !matchedRule.startsWith("base:")) return deterministic;
        Map<String, Object> modelInput = new LinkedHashMap<>();
        modelInput.put("source", primarySource(input));
        modelInput.put("known_categories", List.of("hardware", "network", "software", "reconciliation", "fraud"));
        modelInput.put("known_fields", List.of("event_id", "source", "error_code", "level", "occurred_at", "message",
                "asset_id", "ticket_id", "device_id", "metric_name", "metric_value", "unit", "threshold",
                "asset_type", "location_code", "event_type"));
        modelInput.put("active_rules", snapshot.getOrDefault("rules", List.of()));
        modelInput.put("signals", input.signals().stream().map(AiInterpreterAgent::signalMap).toList());
        JsonNode schema;
        String serialized;
        try {
            schema = json.readTree(SCHEMA);
            serialized = json.writeValueAsString(modelInput);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not prepare AI interpreter input.", e);
        }
        OpenAiResponsesClient.Completion completion = client.completeJson(INSTRUCTIONS, serialized,
                "signalloop_classification", schema, reasoningEffort);
        JsonNode output = completion.json();
        String category = output.path("category").asText("unknown");
        String proposedCategory = output.path("proposed_new_category").asText("").trim();
        String action = output.path("next_action").asText("Escalar para revisión humana.");
        if (!proposedCategory.isEmpty()) action += " Categoría candidata pendiente de revisión: " + proposedCategory + ".";

        Set<String> inputIds = input.signals().stream().map(Signal::eventId).filter(value -> value != null).collect(Collectors.toSet());
        List<String> evidenceIds = new ArrayList<>();
        output.path("evidence_event_ids").forEach(value -> {
            String id = value.asText();
            if (inputIds.contains(id) && !evidenceIds.contains(id)) evidenceIds.add(id);
        });
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "openai"); metadata.put("model", completion.model());
        metadata.put("response_id", completion.responseId()); metadata.put("input_tokens", completion.inputTokens());
        metadata.put("output_tokens", completion.outputTokens()); metadata.put("latency_ms", completion.latencyMs());
        metadata.put("confidence", output.path("confidence").asDouble());
        metadata.put("proposed_new_category", proposedCategory);
        metadata.put("field_mappings", json.convertValue(output.path("field_mappings"), List.class));
        metadata.put("new_field_proposals", json.convertValue(output.path("new_field_proposals"), List.class));
        return new Classification(category, output.path("severity").asText("P2"), action,
                "ai:" + completion.responseId(), List.copyOf(evidenceIds), Map.copyOf(metadata));
    }

    /** Applies only a candidate rule over the already observed baseline output. */
    public Classification classifyCandidate(CaseInput input, Map<String, Object> candidateSnapshot,
                                            Classification baselineOutput) {
        Classification candidate = rules.classify(input, candidateSnapshot);
        String matchedRule = candidate.matchedRule();
        if (matchedRule != null && !matchedRule.equals("unmatched") && !matchedRule.startsWith("base:")) {
            return candidate;
        }
        return baselineOutput;
    }

    private static String primarySource(CaseInput input) {
        return input.signals().stream().map(Signal::source).filter(value -> value != null && !"CORE".equals(value))
                .reduce((first, last) -> last).orElse("UNKNOWN");
    }

    private static Map<String, Object> signalMap(Signal signal) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("event_id", signal.eventId()); value.put("source", signal.source()); value.put("code", signal.code());
        value.put("level", signal.level()); value.put("message", signal.message()); value.put("attributes", signal.attributes());
        return value;
    }
}
