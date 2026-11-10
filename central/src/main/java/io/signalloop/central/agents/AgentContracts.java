package io.signalloop.central.agents;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentContracts {
    private AgentContracts() {}

    public record Signal(String eventId, String source, String code, String level, String message, Map<String, Object> attributes) {}
    public record CaseInput(UUID ticketId, String ticketKey, String split, List<Signal> signals) {}
    public record Expected(String category, String severity, String nextAction, String rootCause) {}
    public record Classification(String category, String severity, String nextAction, String matchedRule, List<String> evidenceEventIds, Map<String, Object> metadata) {}
    public record CaseResult(CaseInput input, Expected expected, Classification output, double score, UUID resultId) {}
    public record LessonProposal(String key, String source, String code, String condition, String action, Map<String, Object> rule, List<UUID> evidenceTicketIds) {}
    public record GateDecision(String decision, double baselineScore, double candidateScore, int improvements, int regressions, String reason) {}
}
