package io.signalloop.central.adapters.jdbc;

import io.signalloop.central.domain.ConflictException;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.NotFoundException;
import io.signalloop.central.domain.RejectedCommandException;
import org.postgresql.util.PSQLException;

import java.sql.SQLException;
import java.util.List;

public final class PgErrors {

    private PgErrors() {
    }

    public static RuntimeException translate(SQLException exception) {
        String state = exception.getSQLState();
        String constraint = constraint(exception);
        if ("23505".equals(state)) {
            return new ConflictException(uniqueCode(constraint), "Unique value already stored");
        }
        if ("23503".equals(state)) {
            return new NotFoundException("REFERENCE_NOT_FOUND", "Referenced row does not exist");
        }
        if ("23514".equals(state) || "23502".equals(state)) {
            String field = constraint == null ? "value" : constraint;
            return new RejectedCommandException(List.of(new FieldViolation(field, "rejected by database constraint")));
        }
        return new IllegalStateException("Database error", exception);
    }

    private static String constraint(SQLException exception) {
        if (exception instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
            return psql.getServerErrorMessage().getConstraint();
        }
        return null;
    }

    private static String uniqueCode(String constraint) {
        if (constraint == null) {
            return "CONFLICT";
        }
        return switch (constraint) {
            case "projects_slug_key" -> "SLUG_CONFLICT";
            case "problem_specs_project_id_key_key" -> "PROBLEM_KEY_CONFLICT";
            case "scenarios_scenario_key_version_key" -> "SCENARIO_CONFLICT";
            case "atms_scenario_id_atm_key_key" -> "ATM_KEY_CONFLICT";
            case "tickets_scenario_id_ticket_key_key" -> "TICKET_KEY_CONFLICT";
            case "expected_outcomes_ticket_id_key" -> "OUTCOME_CONFLICT";
            default -> "CONFLICT";
        };
    }
}
