package io.signalloop.central.agents;

import io.signalloop.central.agents.AgentContracts.CaseResult;
import io.signalloop.central.agents.AgentContracts.LessonProposal;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class LearnerAgent {

    public LessonProposal propose(String source, List<CaseResult> results, Map<String, Object> activeSnapshot) {
        Map<String, List<CaseResult>> failuresByCode = new LinkedHashMap<>();
        for (CaseResult result : results) {
            if (result.score() >= 1.0) continue;
            String code = decisiveCode(result);
            if (code != null && !hasRule(activeSnapshot, source, code)) {
                failuresByCode.computeIfAbsent(code, ignored -> new ArrayList<>()).add(result);
            }
        }
        if (failuresByCode.isEmpty()) return null;

        String preferred = preferredCode(source);
        String selectedCode = failuresByCode.containsKey(preferred)
                ? preferred
                : failuresByCode.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, List<CaseResult>>>comparingInt(entry -> entry.getValue().size()).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
        if (selectedCode == null) return null;

        List<CaseResult> evidence = failuresByCode.get(selectedCode);
        String category = evidence.get(0).expected().category();
        String action = evidence.get(0).expected().nextAction();
        if (evidence.stream().anyMatch(result -> !category.equals(result.expected().category()))) return null;
        String lessonKey = "learned-" + source.toLowerCase() + "-" + selectedCode.toLowerCase().replace('_', '-');
        String condition = "source=" + source + " AND error_code=" + selectedCode;
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source", source);
        rule.put("error_code", selectedCode);
        rule.put("category", category);
        rule.put("next_action", action);
        rule.put("lesson_key", lessonKey);
        List<java.util.UUID> ticketIds = evidence.stream().map(result -> result.input().ticketId()).toList();
        return new LessonProposal(lessonKey, source, selectedCode, condition,
                "Clasificar " + selectedCode + " como " + category + " y recomendar: " + action,
                Map.copyOf(rule), ticketIds);
    }

    private static String decisiveCode(CaseResult result) {
        return result.input().signals().stream().map(signal -> signal.code())
                .filter(code -> code != null && !code.isBlank())
                .filter(code -> !"SIM_INCIDENT_OPENED".equals(code))
                .reduce((first, last) -> last).orElse(null);
    }

    private static boolean hasRule(Map<String, Object> snapshot, String source, String code) {
        Object raw = snapshot.get("rules");
        if (!(raw instanceof List<?> rules)) return false;
        return rules.stream().filter(Map.class::isInstance).map(Map.class::cast).anyMatch(rule ->
                source.equals(String.valueOf(rule.get("source"))) && code.equals(String.valueOf(rule.get("error_code"))));
    }

    private static String preferredCode(String source) {
        return switch (source) {
            case "XFS" -> "SIM_XFS_DUPLICATE_EVENT";
            case "IOT" -> "SIM_IOT_REFRIGERATOR_HEARTBEAT_MISSING";
            case "SNMP" -> "SIM_SNMP_ALARM_INTERMITTENT";
            default -> null;
        };
    }
}
