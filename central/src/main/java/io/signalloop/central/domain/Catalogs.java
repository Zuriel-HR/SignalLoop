package io.signalloop.central.domain;

import java.util.Set;

public final class Catalogs {

    public static final Set<String> SPLITS = Set.of("train", "validation", "test");
    public static final Set<String> TICKET_CATEGORIES = Set.of(
            "hardware", "network", "software", "reconciliation", "fraud");
    public static final Set<String> SEVERITIES = Set.of("P1", "P2", "P3");
    public static final Set<String> LOG_LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    public static final Set<String> SUBSYSTEMS = Set.of(
            "XFS", "IO", "NETWORK", "CORE", "FRAUD", "RECONCILIATION", "IOT", "SNMP");
    public static final Set<String> SCENARIO_STATUSES = Set.of("draft", "generated", "frozen");
    public static final Set<String> AUTHORED_BY = Set.of("simulator", "human");

    private Catalogs() {
    }
}
