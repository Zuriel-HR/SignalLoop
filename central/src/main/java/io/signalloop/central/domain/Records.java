package io.signalloop.central.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class Records {

    private Records() {
    }

    public record Project(UUID id, String slug, String name, String description, OffsetDateTime createdAt) {
    }

    public record ProblemSpec(
            UUID id,
            UUID projectId,
            String key,
            String title,
            String domain,
            String description,
            String evaluatorVersion,
            OffsetDateTime createdAt) {
    }

    public record Scenario(
            UUID id,
            String scenarioKey,
            int version,
            String name,
            String description,
            String domain,
            String simulatorVersion,
            int seed,
            String status,
            OffsetDateTime createdAt) {
    }

    public record Atm(
            UUID id,
            UUID scenarioId,
            String atmKey,
            String locationCode,
            String vendor,
            String model,
            String softwareVersion,
            String xfsVersion,
            OffsetDateTime createdAt) {
    }

    public record Ticket(
            UUID id,
            UUID scenarioId,
            String ticketKey,
            UUID atmId,
            OffsetDateTime occurredAt,
            String category,
            String severity,
            String title,
            String description,
            String customerImpact,
            String split,
            Map<String, Object> metadata,
            OffsetDateTime createdAt) {
    }

    public record SignalLog(
            UUID id,
            UUID scenarioId,
            UUID atmId,
            UUID ticketId,
            OffsetDateTime occurredAt,
            String level,
            String subsystem,
            String errorCode,
            String message,
            Map<String, Object> attributes,
            OffsetDateTime createdAt) {
    }

    public record ExpectedOutcome(
            UUID id,
            UUID ticketId,
            String rootCause,
            String expectedCategory,
            String expectedSeverity,
            String expectedNextAction,
            Map<String, Object> rubric,
            String authoredBy) {
    }
}
