package io.signalloop.central.api;

import io.signalloop.central.agents.OpenAiResponsesClient;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@Path("/api/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
public class AiRuntimeResource {
    private final OpenAiResponsesClient client;
    @ConfigProperty(name = "signalloop.ai.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "signalloop.ai.model", defaultValue = "gpt-6-luna") String model;
    @ConfigProperty(name = "signalloop.ai.reasoning-effort", defaultValue = "medium") String reasoning;
    @ConfigProperty(name = "signalloop.ai.interpreter-reasoning", defaultValue = "low") String interpreterReasoning;
    @ConfigProperty(name = "signalloop.ai.learner-reasoning", defaultValue = "medium") String learnerReasoning;

    public AiRuntimeResource(OpenAiResponsesClient client) {
        this.client = client;
    }

    @GET
    @Path("/status")
    public Map<String, Object> status() {
        return Map.of("provider", "openai", "enabled", enabled,
                "api_key_configured", client.configured(), "model", model,
                "interpreter_reasoning_effort", interpreterReasoning,
                "learner_reasoning_effort", learnerReasoning,
                "default_reasoning_effort", reasoning);
    }
}
