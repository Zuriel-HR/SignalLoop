package io.signalloop.central.agents;

import io.signalloop.central.agents.AgentContracts.CaseInput;
import io.signalloop.central.agents.AgentContracts.Classification;
import io.signalloop.central.agents.AgentContracts.Signal;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class InterpreterAgent {

    public Classification classify(CaseInput input, Map<String, Object> snapshot) {
        List<Signal> signals = input.signals();
        String source = signals.stream()
                .filter(signal -> signal.source() != null && !"CORE".equals(signal.source()))
                .map(Signal::source)
                .reduce((first, second) -> second)
                .orElse(signals.isEmpty() ? "UNKNOWN" : signals.get(signals.size() - 1).source());
        Signal decisive = signals.stream()
                .filter(signal -> signal.code() != null && !signal.code().isBlank())
                .filter(signal -> !"SIM_INCIDENT_OPENED".equals(signal.code()))
                .filter(signal -> signal.source() == null || "CORE".equals(signal.source()) || source.equals(signal.source()))
                .reduce((first, second) -> second)
                .orElse(signals.isEmpty() ? null : signals.get(signals.size() - 1));
        String code = decisive == null ? "" : decisive.code();

        Map<String, Object> matched = findRule(snapshot, source, code);
        String category = matched == null ? baseCategory(source, code) : string(matched.get("category"));
        String severity = inferSeverity(signals);
        String action = matched == null ? actionFor(category, source) : string(matched.get("next_action"));
        if (category == null || category.isBlank()) category = "unknown";
        if (action == null || action.isBlank()) action = "Escalar para revisión y conservar la evidencia del evento.";

        List<String> evidence = new ArrayList<>();
        for (Signal signal : signals) {
            if (signal.eventId() != null && !signal.eventId().isBlank()) evidence.add(signal.eventId());
        }
        return new Classification(category, severity, action,
                matched == null ? (category.equals("unknown") ? "unmatched" : "base:" + source) : string(matched.get("lesson_key")),
                List.copyOf(evidence), Map.of());
    }

    private static Map<String, Object> findRule(Map<String, Object> snapshot, String source, String code) {
        Object raw = snapshot.get("rules");
        if (!(raw instanceof List<?> rules)) return null;
        for (Object item : rules) {
            if (!(item instanceof Map<?, ?> rule)) continue;
            if (!source.equals(string(rule.get("source")))) continue;
            String exact = string(rule.get("error_code"));
            String prefix = string(rule.get("error_code_prefix"));
            if (exact != null && exact.equals(code)) return (Map<String, Object>) rule;
            if (prefix != null && code.startsWith(prefix)) return (Map<String, Object>) rule;
        }
        return null;
    }

    private static String baseCategory(String source, String code) {
        if ("XFS".equals(source)) {
            // V0 intentionally has a broad ATM baseline; duplicate delivery is a seeded failure.
            return "hardware";
        }
        return "unknown";
    }

    private static String inferSeverity(List<Signal> signals) {
        return signals.stream()
                .filter(signal -> "SIM_INCIDENT_OPENED".equals(signal.code()))
                .map(Signal::level)
                .reduce((first, last) -> last)
                .map(level -> "ERROR".equals(level) ? "P1" : "P2")
                .orElse("P2");
    }

    private static String actionFor(String category, String source) {
        return switch (category) {
            case "hardware" -> "Revisar el estado seguro y la telemetría del dispositivo " + source + ".";
            case "network" -> "Revisar conectividad, contadores y disponibilidad de la ruta de red.";
            case "software" -> "Correlacionar IDs de evento y deduplicar antes de abrir otro ticket.";
            case "fraud" -> "Conservar la secuencia de eventos y escalarla a revisión de fraude.";
            default -> "Escalar para revisión y conservar la evidencia del evento.";
        };
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
