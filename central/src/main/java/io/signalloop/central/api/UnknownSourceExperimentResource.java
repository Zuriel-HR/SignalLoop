package io.signalloop.central.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signalloop.central.agents.OpenAiResponsesClient;
import io.signalloop.central.agents.UnknownSourceDiscoveryAgent;
import io.signalloop.central.agents.UnknownSourceProfilerAgent;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in, read-only proof of concept for an unregistered data source. */
@Path("/api/v1/ai/source-discovery")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UnknownSourceExperimentResource {
    private static final int MAX_EVENTS = 12;
    private static final int MAX_INPUT_CHARS = 16_000;

    private final UnknownSourceDiscoveryAgent discovery;
    private final UnknownSourceProfilerAgent profiler;
    private final OpenAiResponsesClient client;
    private final ObjectMapper json;
    @ConfigProperty(name = "signalloop.ai.enabled", defaultValue = "false") boolean aiEnabled;
    @ConfigProperty(name = "signalloop.ai.source-discovery-experiment-enabled", defaultValue = "false") boolean experimentEnabled;

    public UnknownSourceExperimentResource(UnknownSourceDiscoveryAgent discovery,
                                          UnknownSourceProfilerAgent profiler,
                                          OpenAiResponsesClient client, ObjectMapper json) {
        this.discovery = discovery;
        this.profiler = profiler;
        this.client = client;
        this.json = json;
    }

    @POST
    public Map<String, Object> analyze(DiscoveryRequest request) {
        if (!aiEnabled || !experimentEnabled) {
            throw new ServiceUnavailableException("Experimental source discovery is disabled. Enable SIGNALLOOP_AI_ENABLED and SIGNALLOOP_AI_SOURCE_DISCOVERY_EXPERIMENT.");
        }
        if (!client.configured()) throw new ServiceUnavailableException("OPENAI_API_KEY is not configured in the Central process environment.");
        if (request == null || request.events() == null || request.events().isEmpty() || request.events().size() > MAX_EVENTS) {
            throw new BadRequestException("Provide between 1 and " + MAX_EVENTS + " events.");
        }
        try {
            String serialized = json.writeValueAsString(request);
            if (serialized.length() > MAX_INPUT_CHARS) throw new BadRequestException("Experiment input exceeds " + MAX_INPUT_CHARS + " characters.");
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Experiment input must be valid JSON.");
        }
        for (Map<String, Object> event : request.events()) {
            if (event == null || event.get("event_id") == null || event.get("message") == null) {
                throw new BadRequestException("Every event needs event_id and message fields.");
            }
        }

        Map<String, Object> sourceResult = discovery.discover(request.events());
        Map<String, Object> profile = null;
        if ("unknown_source_detected".equals(sourceResult.get("status"))) {
            profile = profiler.profile(String.valueOf(sourceResult.get("source_key_proposal")), request.events());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("experiment_id", UUID.randomUUID().toString());
        response.put("scenario_key", request.scenarioKey() == null ? "ad_hoc_synthetic_source" : request.scenarioKey());
        response.put("result_persisted", false);
        response.put("schema_modified", false);
        response.put("source_detection", sourceResult);
        response.put("incident_profile", profile);
        response.put("note", "Experimental proposal only. Review the source, category, and field mappings before onboarding; no catalog or database changes were made.");
        return response;
    }

    public record DiscoveryRequest(String scenarioKey, List<Map<String, Object>> events) {}
}
