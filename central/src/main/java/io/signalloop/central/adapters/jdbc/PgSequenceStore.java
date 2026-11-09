package io.signalloop.central.adapters.jdbc;

import io.signalloop.central.application.Commands.AtmCommand;
import io.signalloop.central.application.Commands.LogCommand;
import io.signalloop.central.application.Commands.OutcomeCommand;
import io.signalloop.central.application.Commands.ScenarioCommand;
import io.signalloop.central.application.Commands.TicketCommand;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.NotFoundException;
import io.signalloop.central.domain.Records.Atm;
import io.signalloop.central.domain.Records.ExpectedOutcome;
import io.signalloop.central.domain.Records.Scenario;
import io.signalloop.central.domain.Records.SignalLog;
import io.signalloop.central.domain.Records.Ticket;
import io.signalloop.central.domain.RejectedCommandException;
import io.signalloop.central.ports.SequenceStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PgSequenceStore implements SequenceStore {

    private final Pg pg;

    public PgSequenceStore(Pg pg) {
        this.pg = pg;
    }

    @Override
    public Scenario insertScenario(ScenarioCommand command) {
        return pg.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO simulator.scenarios
                        (scenario_key, version, name, description, domain, simulator_version, seed, status)
                    VALUES (?, COALESCE(?::integer, 1), ?, ?, COALESCE(?::text, 'atm_support'),
                            COALESCE(?::text, '0.1.0'), ?, COALESCE(?::text, 'draft'))
                    RETURNING id, scenario_key, version, name, description, domain, simulator_version, seed, status, created_at
                    """)) {
                statement.setString(1, command.scenarioKey());
                if (command.version() == null) {
                    statement.setNull(2, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(2, command.version());
                }
                statement.setString(3, command.name());
                pg.text(statement, 4, command.description());
                pg.text(statement, 5, command.domain());
                pg.text(statement, 6, command.simulatorVersion());
                statement.setInt(7, command.seed());
                pg.text(statement, 8, command.status());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return scenario(rows);
                }
            }
        });
    }

    @Override
    public Optional<Scenario> findScenario(UUID id) {
        return pg.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, scenario_key, version, name, description, domain, simulator_version, seed, status, created_at
                    FROM simulator.scenarios
                    WHERE id = ?
                    """)) {
                statement.setObject(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(scenario(rows));
                }
            }
        });
    }

    @Override
    public Atm insertAtm(UUID scenarioId, AtmCommand command) {
        return pg.transaction(connection -> {
            requireScenario(connection, scenarioId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO simulator.atms
                        (scenario_id, atm_key, location_code, vendor, model, software_version, xfs_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, scenario_id, atm_key, location_code, vendor, model, software_version, xfs_version, created_at
                    """)) {
                statement.setObject(1, scenarioId);
                statement.setString(2, command.atmKey());
                statement.setString(3, command.locationCode());
                statement.setString(4, command.vendor());
                statement.setString(5, command.model());
                statement.setString(6, command.softwareVersion());
                pg.text(statement, 7, command.xfsVersion());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return atm(rows);
                }
            }
        });
    }

    @Override
    public Ticket insertTicket(UUID scenarioId, TicketCommand command) {
        return pg.transaction(connection -> {
            requireScenario(connection, scenarioId);
            requireSameScenario(connection, "simulator.atms", command.atmId(), scenarioId, "atm_id");
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO simulator.tickets
                        (scenario_id, ticket_key, atm_id, occurred_at, category, severity, title, description,
                         customer_impact, split, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, scenario_id, ticket_key, atm_id, occurred_at, category, severity, title, description,
                              customer_impact, split, metadata, created_at
                    """)) {
                statement.setObject(1, scenarioId);
                statement.setString(2, command.ticketKey());
                pg.uuid(statement, 3, command.atmId());
                pg.time(statement, 4, command.occurredAt());
                statement.setString(5, command.category());
                statement.setString(6, command.severity());
                statement.setString(7, command.title());
                statement.setString(8, command.description());
                pg.text(statement, 9, command.customerImpact());
                statement.setString(10, command.split());
                pg.json(statement, 11, command.metadata());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return ticket(rows);
                }
            }
        });
    }

    @Override
    public SignalLog insertLog(UUID scenarioId, LogCommand command) {
        return pg.transaction(connection -> {
            requireScenario(connection, scenarioId);
            requireSameScenario(connection, "simulator.atms", command.atmId(), scenarioId, "atm_id");
            requireSameScenario(connection, "simulator.tickets", command.ticketId(), scenarioId, "ticket_id");
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO simulator.logs
                        (scenario_id, atm_id, ticket_id, occurred_at, level, subsystem, error_code, message, attributes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, scenario_id, atm_id, ticket_id, occurred_at, level, subsystem, error_code, message,
                              attributes, created_at
                    """)) {
                statement.setObject(1, scenarioId);
                statement.setObject(2, command.atmId());
                pg.uuid(statement, 3, command.ticketId());
                pg.time(statement, 4, command.occurredAt());
                statement.setString(5, command.level());
                statement.setString(6, command.subsystem());
                pg.text(statement, 7, command.errorCode());
                statement.setString(8, command.message());
                pg.json(statement, 9, command.attributes());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return log(rows);
                }
            }
        });
    }

    @Override
    public List<SignalLog> logsForAtm(UUID atmId) {
        return pg.transaction(connection -> {
            if (owner(connection, "simulator.atms", atmId) == null) {
                throw new NotFoundException("ATM_NOT_FOUND", "ATM does not exist");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, scenario_id, atm_id, ticket_id, occurred_at, level, subsystem, error_code, message,
                           attributes, created_at
                    FROM simulator.logs
                    WHERE atm_id = ?
                    ORDER BY occurred_at ASC, id ASC
                    """)) {
                statement.setObject(1, atmId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<SignalLog> logs = new ArrayList<>();
                    while (rows.next()) {
                        logs.add(log(rows));
                    }
                    return List.copyOf(logs);
                }
            }
        });
    }

    @Override
    public ExpectedOutcome insertOutcome(UUID ticketId, OutcomeCommand command) {
        return pg.transaction(connection -> {
            if (owner(connection, "simulator.tickets", ticketId) == null) {
                throw new NotFoundException("TICKET_NOT_FOUND", "Ticket does not exist");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO simulator.expected_outcomes
                        (ticket_id, root_cause, expected_category, expected_severity, expected_next_action, rubric, authored_by)
                    VALUES (?, ?, ?, ?, ?, ?, COALESCE(?::text, 'simulator'))
                    RETURNING id, ticket_id, root_cause, expected_category, expected_severity, expected_next_action,
                              rubric, authored_by
                    """)) {
                statement.setObject(1, ticketId);
                statement.setString(2, command.rootCause());
                statement.setString(3, command.expectedCategory());
                statement.setString(4, command.expectedSeverity());
                statement.setString(5, command.expectedNextAction());
                pg.json(statement, 6, command.rubric());
                pg.text(statement, 7, command.authoredBy());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return new ExpectedOutcome(
                            pg.uuid(rows, "id"),
                            pg.uuid(rows, "ticket_id"),
                            rows.getString("root_cause"),
                            rows.getString("expected_category"),
                            rows.getString("expected_severity"),
                            rows.getString("expected_next_action"),
                            pg.readJson(rows, "rubric"),
                            rows.getString("authored_by"));
                }
            }
        });
    }

    private void requireScenario(Connection connection, UUID scenarioId) throws SQLException {
        if (owner(connection, "simulator.scenarios", scenarioId) == null) {
            throw new NotFoundException("SCENARIO_NOT_FOUND", "Scenario does not exist");
        }
    }

    private void requireSameScenario(Connection connection, String table, UUID id, UUID scenarioId, String field)
            throws SQLException {
        if (id == null) {
            return;
        }
        UUID owner = owner(connection, table, id);
        if (owner == null) {
            throw new NotFoundException(field.equals("atm_id") ? "ATM_NOT_FOUND" : "TICKET_NOT_FOUND",
                    field + " does not exist");
        }
        if (!owner.equals(scenarioId)) {
            throw new RejectedCommandException(List.of(new FieldViolation(field, "does not belong to the scenario")));
        }
    }

    private UUID owner(Connection connection, String table, UUID id) throws SQLException {
        String column = table.endsWith("scenarios") ? "id" : "scenario_id";
        if (table.endsWith("scenarios")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM simulator.scenarios WHERE id = ?")) {
                statement.setObject(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? pg.uuid(rows, "id") : null;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? pg.uuid(rows, column) : null;
            }
        }
    }

    private Scenario scenario(ResultSet rows) throws SQLException {
        return new Scenario(
                pg.uuid(rows, "id"),
                rows.getString("scenario_key"),
                rows.getInt("version"),
                rows.getString("name"),
                rows.getString("description"),
                rows.getString("domain"),
                rows.getString("simulator_version"),
                rows.getInt("seed"),
                rows.getString("status"),
                pg.time(rows, "created_at"));
    }

    private Atm atm(ResultSet rows) throws SQLException {
        return new Atm(
                pg.uuid(rows, "id"),
                pg.uuid(rows, "scenario_id"),
                rows.getString("atm_key"),
                rows.getString("location_code"),
                rows.getString("vendor"),
                rows.getString("model"),
                rows.getString("software_version"),
                rows.getString("xfs_version"),
                pg.time(rows, "created_at"));
    }

    private Ticket ticket(ResultSet rows) throws SQLException {
        return new Ticket(
                pg.uuid(rows, "id"),
                pg.uuid(rows, "scenario_id"),
                rows.getString("ticket_key"),
                pg.uuid(rows, "atm_id"),
                pg.time(rows, "occurred_at"),
                rows.getString("category"),
                rows.getString("severity"),
                rows.getString("title"),
                rows.getString("description"),
                rows.getString("customer_impact"),
                rows.getString("split"),
                pg.readJson(rows, "metadata"),
                pg.time(rows, "created_at"));
    }

    private SignalLog log(ResultSet rows) throws SQLException {
        return new SignalLog(
                pg.uuid(rows, "id"),
                pg.uuid(rows, "scenario_id"),
                pg.uuid(rows, "atm_id"),
                pg.uuid(rows, "ticket_id"),
                pg.time(rows, "occurred_at"),
                rows.getString("level"),
                rows.getString("subsystem"),
                rows.getString("error_code"),
                rows.getString("message"),
                pg.readJson(rows, "attributes"),
                pg.time(rows, "created_at"));
    }
}
