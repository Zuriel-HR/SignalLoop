package io.signalloop.central.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.signalloop.central.agents.AgentContracts.CaseResult;
import io.signalloop.central.agents.AgentContracts.LessonProposal;
import io.signalloop.central.agents.AgentContracts.Signal;
import io.signalloop.central.domain.Catalogs;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AiLearnerAgent {
    private static final String INSTRUCTIONS = """
            Eres el Aprendiz de SignalLoop. Analiza exclusivamente los ejemplos de entrenamiento proporcionados.
            Propón una sola regla declarativa que explique errores del intérprete con señales observables y evidencia.
            Propón mappings solo desde claves presentes en attributes hacia known_fields; si no hay destino apropiado, usa new_field_proposals.
            No propongas modificar el esquema SQL ni el banco ni la compuerta. Si el caso requiere una categoría que no aparece en known_categories,
            usa category=unknown y escribe el nombre candidato en new_category_proposal. No inventes códigos: error_code debe estar en observed_error_codes.
            Devuelve solo el JSON solicitado.
            """;
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "lesson_key":{"type":"string"},"source":{"type":"string","enum":["XFS","IOT","SNMP"]},
              "error_code":{"type":"string"},"category":{"type":"string"},"next_action":{"type":"string"},
              "condition":{"type":"string"},"rationale":{"type":"string"},"confidence":{"type":"number","minimum":0,"maximum":1},
              "new_category_proposal":{"type":"string"},"field_mappings":{"type":"array","items":{"type":"object","properties":{
                "source_field":{"type":"string"},"canonical_field":{"type":"string"},"reason":{"type":"string"}},
                "required":["source_field","canonical_field","reason"],"additionalProperties":false}},
              "new_field_proposals":{"type":"array","items":{"type":"object","properties":{
                "source_field":{"type":"string"},"suggested_field":{"type":"string"},"reason":{"type":"string"}},
                "required":["source_field","suggested_field","reason"],"additionalProperties":false}},
              "evidence_ticket_keys":{"type":"array","items":{"type":"string"}}},
              "required":["lesson_key","source","error_code","category","next_action","condition","rationale","confidence","new_category_proposal","field_mappings","new_field_proposals","evidence_ticket_keys"],
              "additionalProperties":false}
            """;

    private final OpenAiResponsesClient client;
    private final ObjectMapper json;
    @ConfigProperty(name = "signalloop.ai.learner-reasoning", defaultValue = "medium") String reasoningEffort;

    public AiLearnerAgent(OpenAiResponsesClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public LessonProposal propose(String source, List<CaseResult> trainingFailures) {
        List<CaseResult> failures = trainingFailures.stream().filter(result -> result.score() < 1.0).toList();
        if (failures.isEmpty()) return null;

        List<String> codes = failures.stream().flatMap(result -> result.input().signals().stream())
                .map(Signal::code).filter(code -> code != null && !code.isBlank() && !"SIM_INCIDENT_OPENED".equals(code))
                .distinct().toList();
        if (codes.isEmpty()) return null;

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("source", source);
        input.put("known_categories", Catalogs.TICKET_CATEGORIES);
        input.put("known_fields", List.of("event_id", "source", "error_code", "level", "occurred_at", "message",
                "asset_id", "ticket_id", "device_id", "metric_name", "metric_value", "unit", "threshold",
                "asset_type", "location_code", "event_type"));
        input.put("observed_error_codes", codes);
        input.put("training_examples", failures.stream().map(AiLearnerAgent::trainingCase).toList());

        JsonNode schema;
        try { schema = json.readTree(SCHEMA); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not prepare AI learner schema.", e); }
        OpenAiResponsesClient.Completion completion;
        try { completion = client.completeJson(INSTRUCTIONS, json.writeValueAsString(input),
                "signalloop_lesson_proposal", schema, reasoningEffort); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize AI learner input.", e); }
        JsonNode result = completion.json();
        String proposedSource = result.path("source").asText();
        String code = result.path("error_code").asText();
        if (!source.equals(proposedSource) || !codes.contains(code)) return null;

        String requestedCategory = result.path("category").asText("unknown");
        String newCategory = result.path("new_category_proposal").asText("").trim();
        boolean recognized = Catalogs.TICKET_CATEGORIES.contains(requestedCategory);
        String effectiveCategory = recognized ? requestedCategory : "unknown";
        if (!recognized && newCategory.isEmpty()) newCategory = requestedCategory;

        List<UUID> evidence = failures.stream()
                .filter(item -> item.input().signals().stream().anyMatch(signal -> code.equals(signal.code())))
                .map(item -> item.input().ticketId()).distinct().toList();
        if (evidence.isEmpty()) return null;

        String lessonKey = result.path("lesson_key").asText("learned-" + source.toLowerCase() + "-" + code.toLowerCase().replace('_', '-'));
        String action = result.path("next_action").asText("Escalar el caso para revisión.");
        String rationale = result.path("rationale").asText("Propuesta de regla basada en ejemplos de entrenamiento.");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source", source); rule.put("error_code", code); rule.put("category", effectiveCategory);
        rule.put("next_action", action); rule.put("lesson_key", lessonKey); rule.put("rationale", rationale);
        rule.put("confidence", result.path("confidence").asDouble());
        rule.put("new_category_proposal", newCategory);
        rule.put("field_mappings", json.convertValue(result.path("field_mappings"), List.class));
        rule.put("new_field_proposals", json.convertValue(result.path("new_field_proposals"), List.class));
        rule.put("ai_model", completion.model()); rule.put("ai_response_id", completion.responseId());
        rule.put("ai_input_tokens", completion.inputTokens()); rule.put("ai_output_tokens", completion.outputTokens());
        rule.put("ai_latency_ms", completion.latencyMs());
        String condition = result.path("condition").asText("source=" + source + " AND error_code=" + code);
        return new LessonProposal(lessonKey, source, code, condition,
                rationale + " Acción propuesta: " + action, Map.copyOf(rule), evidence);
    }

    private static Map<String, Object> trainingCase(CaseResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ticket_key", result.input().ticketKey()); value.put("observed_category", result.output().category());
        value.put("expected_category", result.expected().category()); value.put("expected_action", result.expected().nextAction());
        value.put("expected_root_cause", result.expected().rootCause());
        List<Map<String, Object>> signals = new ArrayList<>();
        for (Signal signal : result.input().signals()) {
            Map<String, Object> event = new LinkedHashMap<>(); event.put("source", signal.source()); event.put("code", signal.code());
            event.put("level", signal.level()); event.put("message", signal.message()); event.put("attributes", signal.attributes()); signals.add(event);
        }
        value.put("signals", signals);
        return value;
    }
}
