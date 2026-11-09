package io.signalloop.central.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class SignalSequenceResourceTest {

    @Test
    void storesTheDispenserSequenceInTimeOrderAndHidesTheExpectedOutcome() {
        String projectId = created("/api/v1/projects", """
                {"slug":"signalloop","name":"SignalLoop","description":"Hackathon"}
                """);
        created("/api/v1/projects/" + projectId + "/problem-specs", """
                {
                  "key":"xfs-dispenser",
                  "title":"Dispenser pre-failure",
                  "domain":"atm_support",
                  "description":"Warn before the dispenser jam",
                  "evaluator_version":"0.1.0"
                }
                """);

        String scenarioId = created("/api/v1/scenarios", """
                {
                  "scenario_key":"xfs-dispenser-prefail",
                  "name":"Dispenser warning sequence",
                  "seed":7
                }
                """);
        given().when().get("/api/v1/scenarios/" + scenarioId)
                .then().statusCode(200)
                .body("domain", equalTo("atm_support"))
                .body("status", equalTo("draft"))
                .body("version", equalTo(1));

        String atmId = created("/api/v1/scenarios/" + scenarioId + "/atms", """
                {
                  "atm_key":"ATM-001",
                  "location_code":"COSTA-01",
                  "vendor":"NCR",
                  "model":"6622",
                  "software_version":"1.0.0",
                  "xfs_version":"3.40"
                }
                """);
        String ticketId = created("/api/v1/scenarios/" + scenarioId + "/tickets", """
                {
                  "ticket_key":"T-JAM-001",
                  "atm_id":"%s",
                  "occurred_at":"2026-11-09T18:02:00Z",
                  "category":"hardware",
                  "severity":"P2",
                  "title":"Dispenser jam",
                  "description":"Bills stopped after two warnings",
                  "split":"train"
                }
                """.formatted(atmId));

        log(scenarioId, atmId, ticketId, "2026-11-09T18:02:00Z", "ERROR", "DISP_JAM");
        log(scenarioId, atmId, null, "2026-11-09T18:00:00Z", "WARN", "DISP_WARN_LOW");
        log(scenarioId, atmId, null, "2026-11-09T18:01:00Z", "WARN", "DISP_JAM_WARN");

        given().when().get("/api/v1/atms/" + atmId + "/logs")
                .then().statusCode(200)
                .body("error_code", equalTo(List.of("DISP_WARN_LOW", "DISP_JAM_WARN", "DISP_JAM")))
                .body("level", equalTo(List.of("WARN", "WARN", "ERROR")));

        created("/api/v1/tickets/" + ticketId + "/expected-outcome", """
                {
                  "root_cause":"dispenser jam",
                  "expected_category":"hardware",
                  "expected_severity":"P2",
                  "expected_next_action":"service the dispenser"
                }
                """);

        given().when().get("/api/v1/atms/" + atmId + "/logs")
                .then().statusCode(200)
                .body(not(containsString("root_cause")))
                .body(not(containsString("dispenser jam")));
    }

    @Test
    void rejectsADuplicateScenarioAndAnUnknownLogLevel() {
        created("/api/v1/scenarios", """
                {"scenario_key":"dup-scenario","name":"Duplicate","seed":1}
                """);
        given().contentType("application/json")
                .body("""
                        {"scenario_key":"dup-scenario","name":"Duplicate again","seed":1}
                        """)
                .when().post("/api/v1/scenarios")
                .then().statusCode(409)
                .body("code", equalTo("SCENARIO_CONFLICT"));

        String scenarioId = created("/api/v1/scenarios", """
                {"scenario_key":"bad-level","name":"Bad level","seed":2}
                """);
        String atmId = created("/api/v1/scenarios/" + scenarioId + "/atms", """
                {
                  "atm_key":"ATM-009",
                  "location_code":"CENTRO",
                  "vendor":"Diebold",
                  "model":"5500",
                  "software_version":"1.0.0"
                }
                """);
        given().contentType("application/json")
                .body("""
                        {
                          "atm_id":"%s",
                          "occurred_at":"2026-11-09T18:00:00Z",
                          "level":"NOISE",
                          "subsystem":"XFS",
                          "message":"not a catalog level"
                        }
                        """.formatted(atmId))
                .when().post("/api/v1/scenarios/" + scenarioId + "/logs")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("fields[0].field", equalTo("level"));
    }

    @Test
    void reportsReadinessAgainstPostgres() {
        given().when().get("/health/ready").then().statusCode(200);
    }

    private static String created(String path, String body) {
        return given().contentType("application/json").body(body)
                .when().post(path)
                .then().statusCode(201)
                .extract().path("id");
    }

    private static void log(String scenarioId, String atmId, String ticketId, String occurredAt, String level, String code) {
        String ticket = ticketId == null ? "null" : "\"" + ticketId + "\"";
        given().contentType("application/json")
                .body("""
                        {
                          "atm_id":"%s",
                          "ticket_id":%s,
                          "occurred_at":"%s",
                          "level":"%s",
                          "subsystem":"XFS",
                          "error_code":"%s",
                          "message":"%s"
                        }
                        """.formatted(atmId, ticket, occurredAt, level, code, code))
                .when().post("/api/v1/scenarios/" + scenarioId + "/logs")
                .then().statusCode(201);
    }
}
