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

/** Experimental agent: separates source identification from incident categorization. */
@ApplicationScoped
public class UnknownSourceDiscoveryAgent {
    private static final String INSTRUCTIONS = """
            Eres el agente experimental de descubrimiento de fuentes de SignalLoop.
            Compara el protocolo y la estructura de los eventos con los perfiles conocidos entregados.
            XFS significa eventos de servicios de ATM; IOT significa telemetría/heartbeats de activos; SNMP significa traps, OID y varbinds de red.
            No clasifiques la causa del incidente aquí. Una telemetría industrial con un protocolo y un esquema distintos no debe marcarse como IOT solo por venir de un dispositivo.
            Si no hay evidencia suficiente para afirmar que es una de las fuentes conocidas, devuelve matched_known_source=UNKNOWN y propone un source_key nuevo en mayúsculas con guiones bajos.
            Usa solo IDs de eventos recibidos como evidencia. No registres ni des por aprobada la fuente nueva.
            """;
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "matched_known_source":{"type":"string","enum":["XFS","IOT","SNMP","UNKNOWN"]},
              "source_key_proposal":{"type":"string"},
              "source_name_proposal":{"type":"string"},
              "protocol_family":{"type":"string"},
              "confidence":{"type":"number","minimum":0,"maximum":1},
              "reason":{"type":"string"},
              "evidence_event_ids":{"type":"array","items":{"type":"string"}},
              "observed_fields":{"type":"array","items":{"type":"string"}}},
              "required":["matched_known_source","source_key_proposal","source_name_proposal","protocol_family","confidence","reason","evidence_event_ids","observed_fields"],
              "additionalProperties":false}
            """;

    private final OpenAiResponsesClient client;
    private final ObjectMapper json;
    @ConfigProperty(name = "signalloop.ai.discovery-reasoning", defaultValue = "low") String reasoningEffort;

    public UnknownSourceDiscoveryAgent(OpenAiResponsesClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public Map<String, Object> discover(List<Map<String, Object>> events) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("known_sources", List.of(
                Map.of("key", "XFS", "profile", "ATM XFS service events, device/service request IDs, operation event IDs"),
                Map.of("key", "IOT", "profile", "asset telemetry and heartbeat measurements, commonly MQTT/HTTP"),
                Map.of("key", "SNMP", "profile", "network traps, OIDs, varbinds, interface or UPS status")));
        input.put("events", events);
        try {
            JsonNode schema = json.readTree(SCHEMA);
            OpenAiResponsesClient.Completion completion = client.completeJson(INSTRUCTIONS,
                    json.writeValueAsString(input), "signalloop_source_discovery", schema, reasoningEffort);
            JsonNode out = completion.json();
            String matched = out.path("matched_known_source").asText("UNKNOWN");
            if (!Set.of("XFS", "IOT", "SNMP", "UNKNOWN").contains(matched)) matched = "UNKNOWN";
            String sourceKey = normalizeKey(out.path("source_key_proposal").asText(""));
            if ("UNKNOWN".equals(matched) && sourceKey.isBlank()) sourceKey = "NEW_SOURCE_UNCLASSIFIED";
            Set<String> eventIds = events.stream().map(event -> event.get("event_id"))
                    .filter(value -> value != null).map(Object::toString).collect(Collectors.toSet());
            List<String> evidence = json.convertValue(out.path("evidence_event_ids"), List.class);
            evidence = evidence == null ? List.of() : evidence.stream().filter(eventIds::contains).distinct().toList();
            Set<String> observed = events.stream().flatMap(event -> {
                Set<String> fields = new java.util.HashSet<>(event.keySet());
                Object attributes = event.get("attributes");
                if (attributes instanceof Map<?, ?> map) {
                    map.keySet().stream().map(Object::toString).map(key -> "attributes." + key).forEach(fields::add);
                }
                return fields.stream();
            }).collect(Collectors.toSet());
            List<String> observedFields = observed.stream().sorted().toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UNKNOWN".equals(matched) ? "unknown_source_detected" : "known_source_match");
            result.put("matched_known_source", matched); result.put("source_key_proposal", sourceKey);
            result.put("source_name_proposal", out.path("source_name_proposal").asText(""));
            result.put("protocol_family", out.path("protocol_family").asText(""));
            result.put("confidence", out.path("confidence").asDouble()); result.put("reason", out.path("reason").asText(""));
            result.put("evidence_event_ids", evidence);
            result.put("observed_fields", observedFields);
            result.put("proposal_only", true);
            result.put("model", completion.model()); result.put("response_id", completion.responseId());
            result.put("input_tokens", completion.inputTokens()); result.put("output_tokens", completion.outputTokens());
            result.put("latency_ms", completion.latencyMs());
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not prepare source discovery request.", e);
        }
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
