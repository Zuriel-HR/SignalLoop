package io.signalloop.central.application;

import io.signalloop.central.application.Commands.AtmCommand;
import io.signalloop.central.application.Commands.LogCommand;
import io.signalloop.central.application.Commands.OutcomeCommand;
import io.signalloop.central.application.Commands.ScenarioCommand;
import io.signalloop.central.application.Commands.TicketCommand;
import io.signalloop.central.domain.Catalogs;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.NotFoundException;
import io.signalloop.central.domain.Records.Atm;
import io.signalloop.central.domain.Records.ExpectedOutcome;
import io.signalloop.central.domain.Records.Scenario;
import io.signalloop.central.domain.Records.SignalLog;
import io.signalloop.central.domain.Records.Ticket;
import io.signalloop.central.ports.SequenceStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SequenceService {

    private final SequenceStore sequences;

    public SequenceService(SequenceStore sequences) {
        this.sequences = sequences;
    }

    public Scenario createScenario(ScenarioCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        String key = CommandChecks.required(command.scenarioKey(), "scenario_key", violations);
        String name = CommandChecks.required(command.name(), "name", violations);
        if (command.seed() == null) {
            violations.add(new FieldViolation("seed", "required"));
        }
        if (command.version() != null && command.version() < 1) {
            violations.add(new FieldViolation("version", "must be greater than 0"));
        }
        CommandChecks.oneOf(command.status(), "status", Catalogs.SCENARIO_STATUSES, violations);
        CommandChecks.require(violations);
        return sequences.insertScenario(new ScenarioCommand(
                key,
                command.version(),
                name,
                CommandChecks.optional(command.description()),
                CommandChecks.optional(command.domain()),
                CommandChecks.optional(command.simulatorVersion()),
                command.seed(),
                CommandChecks.optional(command.status())));
    }

    public Scenario getScenario(UUID id) {
        return sequences.findScenario(id)
                .orElseThrow(() -> new NotFoundException("SCENARIO_NOT_FOUND", "Scenario does not exist"));
    }

    public Atm addAtm(UUID scenarioId, AtmCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        String atmKey = CommandChecks.required(command.atmKey(), "atm_key", violations);
        String location = CommandChecks.required(command.locationCode(), "location_code", violations);
        String vendor = CommandChecks.required(command.vendor(), "vendor", violations);
        String model = CommandChecks.required(command.model(), "model", violations);
        String software = CommandChecks.required(command.softwareVersion(), "software_version", violations);
        CommandChecks.require(violations);
        return sequences.insertAtm(scenarioId, new AtmCommand(
                atmKey, location, vendor, model, software, CommandChecks.optional(command.xfsVersion())));
    }

    public Ticket addTicket(UUID scenarioId, TicketCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        String ticketKey = CommandChecks.required(command.ticketKey(), "ticket_key", violations);
        if (command.occurredAt() == null) {
            violations.add(new FieldViolation("occurred_at", "required"));
        }
        CommandChecks.catalogTicket(command.category(), command.severity(), command.split(), violations);
        String title = CommandChecks.required(command.title(), "title", violations);
        String description = CommandChecks.required(command.description(), "description", violations);
        CommandChecks.require(violations);
        return sequences.insertTicket(scenarioId, new TicketCommand(
                ticketKey,
                command.atmId(),
                command.occurredAt(),
                command.category(),
                command.severity(),
                title,
                description,
                CommandChecks.optional(command.customerImpact()),
                command.split(),
                command.metadata()));
    }

    public SignalLog appendLog(UUID scenarioId, LogCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        if (command.atmId() == null) {
            violations.add(new FieldViolation("atm_id", "required"));
        }
        if (command.occurredAt() == null) {
            violations.add(new FieldViolation("occurred_at", "required"));
        }
        CommandChecks.requiredOneOf(command.level(), "level", Catalogs.LOG_LEVELS, violations);
        CommandChecks.requiredOneOf(command.subsystem(), "subsystem", Catalogs.SUBSYSTEMS, violations);
        String message = CommandChecks.required(command.message(), "message", violations);
        CommandChecks.require(violations);
        return sequences.insertLog(scenarioId, new LogCommand(
                command.atmId(),
                command.ticketId(),
                command.occurredAt(),
                command.level(),
                command.subsystem(),
                CommandChecks.optional(command.errorCode()),
                message,
                command.attributes()));
    }

    public List<SignalLog> sequence(UUID atmId) {
        return sequences.logsForAtm(atmId);
    }

    public ExpectedOutcome addOutcome(UUID ticketId, OutcomeCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        String cause = CommandChecks.required(command.rootCause(), "root_cause", violations);
        CommandChecks.requiredOneOf(command.expectedCategory(), "expected_category", Catalogs.TICKET_CATEGORIES, violations);
        CommandChecks.requiredOneOf(command.expectedSeverity(), "expected_severity", Catalogs.SEVERITIES, violations);
        String action = CommandChecks.required(command.expectedNextAction(), "expected_next_action", violations);
        CommandChecks.oneOf(command.authoredBy(), "authored_by", Catalogs.AUTHORED_BY, violations);
        CommandChecks.require(violations);
        return sequences.insertOutcome(ticketId, new OutcomeCommand(
                cause,
                command.expectedCategory(),
                command.expectedSeverity(),
                action,
                command.rubric(),
                CommandChecks.optional(command.authoredBy())));
    }
}
