package io.signalloop.central.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BuilderAgent {
    private final ObjectMapper json;

    public BuilderAgent(ObjectMapper json) {
        this.json = json;
    }

    public Map<String, Object> buildSuccessor(Map<String, Object> parent, Map<String, Object> acceptedRule, int generationNo) {
        Map<String, Object> successor = new LinkedHashMap<>(parent);
        successor.put("version", "v" + generationNo);
        successor.put("parent_version", parent.getOrDefault("version", "v0"));
        successor.put("created_by", "system");
        successor.put("build_mode", "declarative_snapshot");
        List<Map<String, Object>> rules = new ArrayList<>();
        Object existing = parent.get("rules");
        if (existing instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> map) rules.add(cast(map));
            }
        }
        String lessonKey = String.valueOf(acceptedRule.get("lesson_key"));
        if (rules.stream().noneMatch(rule -> lessonKey.equals(rule.get("lesson_key")))) {
            rules.add(new LinkedHashMap<>(acceptedRule));
        }
        successor.put("rules", List.copyOf(rules));
        successor.put("inherited_lessons", rules.stream().map(rule -> rule.get("lesson_key"))
                .filter(value -> value != null).toList());
        successor.put("config_hash", hash(successor));
        return successor;
    }

    public String hash(Map<String, Object> snapshot) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>(snapshot);
            canonical.remove("config_hash");
            byte[] bytes = json.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash successor snapshot", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
