package io.signalloop.central.application;

import io.signalloop.central.domain.Catalogs;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.RejectedCommandException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CommandChecks {

    private CommandChecks() {
    }

    public static void require(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new RejectedCommandException(violations);
        }
    }

    public static String required(String value, String field, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            violations.add(new FieldViolation(field, "required"));
            return null;
        }
        return value.trim();
    }

    public static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static void oneOf(String value, String field, Set<String> allowed, List<FieldViolation> violations) {
        if (value == null) {
            return;
        }
        if (!allowed.contains(value)) {
            violations.add(new FieldViolation(field, "must be one of " + String.join(", ", allowed)));
        }
    }

    public static void requiredOneOf(String value, String field, Set<String> allowed, List<FieldViolation> violations) {
        if (required(value, field, violations) != null) {
            oneOf(value, field, allowed, violations);
        }
    }

    public static List<FieldViolation> start() {
        return new ArrayList<>();
    }

    public static void catalogTicket(String category, String severity, String split, List<FieldViolation> violations) {
        requiredOneOf(category, "category", Catalogs.TICKET_CATEGORIES, violations);
        requiredOneOf(severity, "severity", Catalogs.SEVERITIES, violations);
        requiredOneOf(split, "split", Catalogs.SPLITS, violations);
    }
}
