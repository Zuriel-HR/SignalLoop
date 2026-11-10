package io.signalloop.central.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal Responses API client. It never logs prompts, responses, or the API key. */
@ApplicationScoped
public class OpenAiResponsesClient {
    private static final Logger LOG = Logger.getLogger(OpenAiResponsesClient.class);
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");

    @Inject ObjectMapper json;
    @ConfigProperty(name = "signalloop.ai.api-key", defaultValue = "") String apiKey;
    @ConfigProperty(name = "signalloop.ai.model", defaultValue = "gpt-6-luna") String model;
    @ConfigProperty(name = "signalloop.ai.reasoning-effort", defaultValue = "medium") String reasoningEffort;
    @ConfigProperty(name = "signalloop.ai.timeout-seconds", defaultValue = "45") int timeoutSeconds;

    private HttpClient http;

    @PostConstruct
    void initialize() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).build();
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Completion completeJson(String instructions, String inputJson, String schemaName, JsonNode schema) {
        return completeJson(instructions, inputJson, schemaName, schema, reasoningEffort);
    }

    public Completion completeJson(String instructions, String inputJson, String schemaName, JsonNode schema,
                                   String requestReasoningEffort) {
        if (!configured()) throw new IllegalStateException("OPENAI_API_KEY is not configured in the Central process environment.");
        long started = System.nanoTime();
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("store", false);
        body.put("instructions", instructions);
        body.put("input", inputJson);
        body.putObject("reasoning").put("effort", requestReasoningEffort);
        ObjectNode format = body.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", schemaName);
        format.put("strict", true);
        format.set("schema", schema);

        try {
            HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("OpenAI Responses API request failed: model=%s status=%d", model, response.statusCode());
                throw new IllegalStateException("OpenAI Responses API returned HTTP " + response.statusCode() + ". Check API access, model ID, and API billing.");
            }
            JsonNode root = json.readTree(response.body());
            String text = outputText(root);
            JsonNode result = json.readTree(text);
            String responseModel = root.path("model").asText(model);
            String responseId = root.path("id").asText("");
            int inputTokens = root.path("usage").path("input_tokens").asInt(0);
            int outputTokens = root.path("usage").path("output_tokens").asInt(0);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            LOG.infof("OpenAI Responses API request completed: model=%s response_id=%s input_tokens=%d output_tokens=%d latency_ms=%d",
                    responseModel, responseId, inputTokens, outputTokens, latencyMs);
            return new Completion(result, responseModel, responseId,
                    inputTokens, outputTokens, latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("OpenAI Responses API request interrupted: model=%s", model);
            throw new IllegalStateException("OpenAI request was interrupted.", e);
        } catch (IOException e) {
            LOG.warnf("OpenAI Responses API transport or parsing error: model=%s error_type=%s", model, e.getClass().getSimpleName());
            throw new IllegalStateException("Could not reach or parse the OpenAI Responses API.", e);
        }
    }

    private static String outputText(JsonNode response) {
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) return content.path("text").asText();
            }
        }
        throw new IllegalStateException("OpenAI returned no structured text output.");
    }

    public record Completion(JsonNode json, String model, String responseId,
                             int inputTokens, int outputTokens, long latencyMs) {}
}
