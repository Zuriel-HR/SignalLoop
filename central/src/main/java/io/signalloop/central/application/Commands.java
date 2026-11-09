package io.signalloop.central.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class Commands {

    private Commands() {
    }

    public record ProjectCommand(String slug, String name, String description) {
    }

    public record ProblemSpecCommand(
            String key, String title, String domain, String description, String evaluatorVersion) {
    }

    public record ScenarioCommand(
            String scenarioKey,
            Integer version,
            String name,
            String description,
            String domain,
            String simulatorVersion,
            Integer seed,
            String status) {
    }

    public record AtmCommand(
            String atmKey,
            String locationCode,
            String vendor,
            String model,
            String softwareVersion,
            String xfsVersion) {
    }

    public record TicketCommand(
            String ticketKey,
            UUID atmId,
            OffsetDateTime occurredAt,
            String category,
            String severity,
            String title,
            String description,
            String customerImpact,
            String split,
            Map<String, Object> metadata) {
    }

    public record LogCommand(
            UUID atmId,
            UUID ticketId,
            OffsetDateTime occurredAt,
            String level,
            String subsystem,
            String errorCode,
            String message,
            Map<String, Object> attributes) {
    }

    public record OutcomeCommand(
            String rootCause,
            String expectedCategory,
            String expectedSeverity,
            String expectedNextAction,
            Map<String, Object> rubric,
            String authoredBy) {
    }
}
