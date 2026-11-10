package io.signalloop.central.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Proposes an incident category and field contract for an unregistered source. */
@ApplicationScoped
public class UnknownSourceProfilerAgent {
    private static final Set<String> CATEGORIES = Set.of("hardware", "network", "software", "reconciliation", "fraud", "unknown");
    private static final Set<String> CANONICAL_FIELDS = Set.of("event_id", "source", "error_code", "level", "occurred_at",
            "message", "asset_id", "ticket_id", "device_id", "metric_name", "metric_value", "unit", "threshold",
            "asset_type", "location_code", "event_type");
    private static final String INSTRUCTIONS = """
            Eres el agente experimental que perfila una fuente nueva para SignalLoop.
            La fuente aún no está registrada. Clasifica el incidente usando el catálogo de categorías existente, sin inventar categorías.
            Sugiere mappings de campos observados hacia el contrato canónico, y separa los campos que requieren extensión del contrato.
            No asumas que una fuente desconocida es IoT. Basa la categoría en el síntoma y la evidencia, no solo en el protocolo.
            No alteres el payload ni marques la fuente como aprobada. Devuelve IDs de evidencia tomados exclusivamente de los eventos proporcionados.
            """;
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "incident_category":{"type":"string","enum":["hardware","network","software","reconciliation","fraud","unknown"]},
              "severity":{"type":"string","enum":["P1","P2","P3"]},
              "incident_type":{"type":"string"},
              "operational_summary":{"type":"string"},
              "field_mappings":{"type":"array","items":{"type":"object","properties":{
                "source_field":{"type":"string"},"canonical_field":{"type":"string"},"reason":{"type":"string"}},
                "required":["source_field","canonical_field","reason"],"additionalProperties":false}},
              "new_field_proposals":{"type":"array","items":{"type":"object","properties":{
                "source_field":{"type":"string"},"suggested_field":{"type":"string"},"reason":{"type":"string"}},
                "required":["source_field","suggested_field","reason"],"additionalProperties":false}},
              "evidence_event_ids":{"type":"array","items":{"type":"string"}},
              "onboarding_checks":{"type":"array","items":{"type":"string"}}},
              "required":["incident_category","severity","incident_type","operational_summary","field_mappings","new_field_proposals","evidence_event_ids","onboarding_checks"],
              "additionalProperties":false}
            """;

    private final OpenAiResponsesClient client;
    private final ObjectMapper json;
    @ConfigProperty(name = "signalloop.ai.discovery-reasoning", defaultValue = "low") String reasoningEffort;

    public UnknownSourceProfilerAgent(OpenAiResponsesClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public Map<String, Object> profile(String proposedSource, List<Map<String, Object>> events) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("proposed_source", proposedSource); input.put("known_categories", CATEGORIES);
        input.put("canonical_fields", CANONICAL_FIELDS); input.put("events", events);
        try {
            JsonNode schema = json.readTree(SCHEMA);
            OpenAiResponsesClient.Completion completion = client.completeJson(INSTRUCTIONS,
                    json.writeValueAsString(input), "signalloop_source_profile", schema, reasoningEffort);
            JsonNode out = completion.json();
            String category = out.path("incident_category").asText("unknown");
            if (!CATEGORIES.contains(category)) category = "unknown";
            Set<String> observed = events.stream().flatMap(event -> {
                Set<String> fields = new java.util.HashSet<>(event.keySet());
                Object attributes = event.get("attributes");
                if (attributes instanceof Map<?, ?> map) {
                    map.keySet().stream().map(Object::toString).forEach(fields::add);
                    map.keySet().stream().map(Object::toString).map(key -> "attributes." + key).forEach(fields::add);
                }
                return fields.stream();
            }).collect(Collectors.toSet());
            List<Map<String, Object>> mappings = json.convertValue(out.path("field_mappings"), List.class);
            if (mappings == null) mappings = List.of();
            List<Map<String, Object>> safeMappings = mappings.stream().filter(mapping ->
                    observed.contains(String.valueOf(mapping.get("source_field")))
                            && CANONICAL_FIELDS.contains(String.valueOf(mapping.get("canonical_field")))).toList();
            Set<String> eventIds = events.stream().map(event -> event.get("event_id"))
                    .filter(value -> value != null).map(Object::toString).collect(Collectors.toSet());
            List<String> evidence = json.convertValue(out.path("evidence_event_ids"), List.class);
            evidence = evidence == null ? List.of() : evidence.stream().filter(eventIds::contains).distinct().toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("incident_category", category); result.put("severity", out.path("severity").asText("P2"));
            result.put("incident_type", out.path("incident_type").asText(""));
            result.put("operational_summary", out.path("operational_summary").asText(""));
            result.put("field_mappings", safeMappings);
            result.put("new_field_proposals", json.convertValue(out.path("new_field_proposals"), List.class));
            result.put("evidence_event_ids", evidence);
            result.put("onboarding_checks", json.convertValue(out.path("onboarding_checks"), List.class));
            result.put("registration_status", "proposal_only"); result.put("confidence", "review_required");
            result.put("model", completion.model()); result.put("response_id", completion.responseId());
            result.put("input_tokens", completion.inputTokens()); result.put("output_tokens", completion.outputTokens());
            result.put("latency_ms", completion.latencyMs());
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not prepare unknown source profile request.", e);
        }
    }
}
