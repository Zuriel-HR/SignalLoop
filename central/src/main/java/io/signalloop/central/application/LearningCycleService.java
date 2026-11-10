package io.signalloop.central.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signalloop.central.adapters.jdbc.Pg;
import io.signalloop.central.agents.AgentContracts.CaseInput;
import io.signalloop.central.agents.AgentContracts.CaseResult;
import io.signalloop.central.agents.AgentContracts.Expected;
import io.signalloop.central.agents.AgentContracts.LessonProposal;
import io.signalloop.central.agents.AgentContracts.Signal;
import io.signalloop.central.agents.AiInterpreterAgent;
import io.signalloop.central.agents.AiLearnerAgent;
import io.signalloop.central.agents.BuilderAgent;
import io.signalloop.central.agents.EvaluatorAgent;
import io.signalloop.central.agents.GateAgent;
import io.signalloop.central.agents.LearnerAgent;
import io.signalloop.central.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coordinates deterministic runtime agents and records their full decision trail. */
@ApplicationScoped
public class LearningCycleService {
    private static final String PROJECT_SLUG = "signalloop";
    private static final String SPEC_KEY = "six-case-multisource-learning";
    private static final String SYSTEM_NAME = "SignalLoop Interpreter";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Pg pg;
    private final ObjectMapper json;
    private final EvaluatorAgent evaluator;
    private final LearnerAgent learner;
    private final AiLearnerAgent aiLearner;
    private final AiInterpreterAgent aiInterpreter;
    private final GateAgent gate;
    private final BuilderAgent builder;
    @ConfigProperty(name = "signalloop.ai.enabled", defaultValue = "false") boolean aiEnabled;

    public LearningCycleService(Pg pg, ObjectMapper json, EvaluatorAgent evaluator,
                                LearnerAgent learner, AiLearnerAgent aiLearner, AiInterpreterAgent aiInterpreter,
                                GateAgent gate, BuilderAgent builder) {
        this.pg = pg;
        this.json = json;
        this.evaluator = evaluator;
        this.learner = learner;
        this.aiLearner = aiLearner;
        this.aiInterpreter = aiInterpreter;
        this.gate = gate;
        this.builder = builder;
    }

    public Map<String, Object> run(UUID scenarioId, String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase();
        if (!List.of("XFS", "IOT", "SNMP").contains(normalized)) {
            throw new BadRequestException("source must be XFS, IOT, or SNMP");
        }
        if (aiEnabled && !aiInterpreter.isConfigured()) {
            throw new IllegalStateException("SIGNALLOOP_AI_ENABLED is true but OPENAI_API_KEY is not configured.");
        }
        return pg.transaction(connection -> runCycle(connection, scenarioId, normalized, aiEnabled));
    }

    private Map<String, Object> runCycle(Connection c, UUID scenarioId, String source, boolean aiMode) throws SQLException {
        UUID projectId = ensureProject(c);
        String track = aiMode ? "ai" : "deterministic";
        UUID specId = ensureSpec(c, projectId, aiMode);
        UUID systemId = ensureSystem(c, projectId, specId, aiMode);
        ensureDatasets(c, specId, scenarioId, track);
        Generation parent = currentGeneration(c, systemId);
        List<TrainingCase> train = cases(c, scenarioId, "train", source);
        List<TrainingCase> validation = cases(c, scenarioId, "validation", null);
        if (train.isEmpty() || validation.isEmpty()) {
            throw new IllegalStateException("The scenario needs source training cases and a non-empty validation split.");
        }

        Map<String, Expected> trainExpected = expectedMap(train);
        List<CaseResult> trainResults = evaluate(train, trainExpected, parent.config(), aiMode);
        UUID runGroup = UUID.randomUUID();
        UUID trainDataset = datasetId(c, specId, "master-prompt-six-" + track + "-train");
        UUID validationDataset = datasetId(c, specId, "master-prompt-six-" + track + "-validation");
        UUID trainRun = startRun(c, systemId, parent.id(), trainDataset, runGroup);
        List<CaseResult> persistedTrain = saveResults(c, trainRun, trainResults);
        finishRun(c, trainRun, mean(persistedTrain));

        Map<String, Expected> validationExpected = expectedMap(validation);
        List<CaseResult> before = evaluate(validation, validationExpected, parent.config(), aiMode);
        UUID baselineRun = startRun(c, systemId, parent.id(), validationDataset, runGroup);
        List<CaseResult> persistedBefore = saveResults(c, baselineRun, before);
        finishRun(c, baselineRun, mean(persistedBefore));

        LessonProposal proposal = aiMode ? aiLearner.propose(source, trainResults) : learner.propose(source, trainResults, parent.config());
        if (proposal == null) {
            Map<String, Object> noChange = new LinkedHashMap<>();
            noChange.put("source", source); noChange.put("mode", track); noChange.put("generation", parent.number());
            noChange.put("decision", "no_change"); noChange.put("baseline_validation_accuracy", mean(persistedBefore));
            noChange.put("message", "No new training error was found for this source.");
            return noChange;
        }
        if (aiMode) addModelUsage(c, trainRun, proposal.rule());

        int nextNumber = nextGenerationNumber(c, systemId);
        Map<String, Object> candidateConfig = builder.buildSuccessor(parent.config(), proposal.rule(), nextNumber);
        UUID candidateId = insertGeneration(c, systemId, nextNumber, parent.id(), candidateConfig,
                "lessons", "Aprendiz propone regla para " + source + ": " + proposal.code(), proposal.condition(), "evaluating");
        List<CaseResult> after = aiMode
                ? evaluateCandidate(validation, validationExpected, candidateConfig, persistedBefore)
                : evaluate(validation, validationExpected, candidateConfig, false);
        UUID candidateRun = startRun(c, systemId, candidateId, validationDataset, runGroup);
        List<CaseResult> persistedAfter = saveResults(c, candidateRun, after);
        finishRun(c, candidateRun, mean(persistedAfter));

        var decision = gate.decide(before, after);
        persistProposal(c, projectId, specId, proposal, trainResults, persistedTrain,
                systemId, parent, candidateId, runGroup, decision);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", source);
        response.put("mode", track);
        response.put("generation", decision.decision().equals("accepted") ? nextNumber : parent.number());
        response.put("lesson", proposal.key());
        response.put("code", proposal.code());
        response.put("decision", decision.decision());
        response.put("baseline_validation_accuracy", decision.baselineScore());
        response.put("candidate_validation_accuracy", decision.candidateScore());
        response.put("improvements", decision.improvements());
        response.put("regressions", decision.regressions());
        response.put("reason", decision.reason());
        return response;
    }

    private UUID ensureProject(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO project.projects (slug,name,description) VALUES (?,'SignalLoop','Sistema de aprendizaje incremental para incidencias XFS, IoT y SNMP')
                ON CONFLICT (slug) DO UPDATE SET name=EXCLUDED.name RETURNING id
                """)) {
            s.setString(1, PROJECT_SLUG);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        }
    }

    private UUID ensureSpec(Connection c, UUID projectId, boolean aiMode) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO project.problem_specs (project_id,key,title,domain,description,evaluator_version)
                VALUES (?,?,?,'device_operations',?,'category-accuracy-v1')
                ON CONFLICT (project_id,key) DO UPDATE SET title=EXCLUDED.title RETURNING id
                """)) {
            s.setObject(1, projectId); s.setString(2, aiMode ? SPEC_KEY + "-ai" : SPEC_KEY);
            s.setString(3, aiMode ? "Clasificación IA multi-fuente" : "Clasificación determinista multi-fuente");
            s.setString(4, "Banco fijo del master prompt con seis casos, tres fuentes y etiquetas aisladas del intérprete.");
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        }
    }

    private UUID ensureSystem(Connection c, UUID projectId, UUID specId, boolean aiMode) throws SQLException {
        String systemName = aiMode ? SYSTEM_NAME + " AI" : SYSTEM_NAME;
        try (PreparedStatement s = c.prepareStatement("""
                SELECT id FROM project.agent_systems WHERE project_id=? AND problem_spec_id=? AND name=? AND role='solver' LIMIT 1
                """)) {
            s.setObject(1, projectId); s.setObject(2, specId); s.setString(3, systemName);
            try (ResultSet r = s.executeQuery()) { if (r.next()) return r.getObject(1, UUID.class); }
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO project.agent_systems (project_id,problem_spec_id,name,role) VALUES (?, ?, ?, 'solver') RETURNING id")) {
            s.setObject(1, projectId); s.setObject(2, specId); s.setString(3, systemName);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        }
    }

    private void ensureDatasets(Connection c, UUID specId, UUID scenarioId, String track) throws SQLException {
        for (String split : List.of("train", "validation")) {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO project.datasets (problem_spec_id,source_scenario_id,name,split,source,simulator_version,seed,item_count,content_hash)
                    SELECT ?, ?, ?, ?, 'synthetic', s.simulator_version, s.seed, count(t.id)::int, md5(string_agg(t.ticket_key,',' ORDER BY t.ticket_key))
                    FROM simulator.scenarios s LEFT JOIN simulator.tickets t ON t.scenario_id=s.id AND t.split=?
                    WHERE s.id=? GROUP BY s.id
                    ON CONFLICT (problem_spec_id,name) DO UPDATE SET source_scenario_id=EXCLUDED.source_scenario_id,split=EXCLUDED.split,simulator_version=EXCLUDED.simulator_version,seed=EXCLUDED.seed,item_count=EXCLUDED.item_count,content_hash=EXCLUDED.content_hash
                    """)) {
                s.setObject(1, specId); s.setObject(2, scenarioId); s.setString(3, "master-prompt-six-" + track + "-" + split);
                s.setString(4, split); s.setString(5, split); s.setObject(6, scenarioId);
                if (s.executeUpdate() != 1) throw new NotFoundException("SCENARIO_NOT_FOUND", "Scenario does not exist");
            }
        }
    }

    private Generation currentGeneration(Connection c, UUID systemId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id,generation_no,config FROM project.generations WHERE system_id=? AND status IN ('baseline','accepted') ORDER BY generation_no DESC LIMIT 1")) {
            s.setObject(1, systemId);
            try (ResultSet r = s.executeQuery()) { if (r.next()) return new Generation(r.getObject("id", UUID.class), r.getInt("generation_no"), readMap(r.getString("config"))); }
        }
        Map<String, Object> baseline = new LinkedHashMap<>(); baseline.put("version", "v0"); baseline.put("rules", List.of());
        UUID id = insertGeneration(c, systemId, 0, null, baseline, null, "Initial broad-source baseline", "Initial classification without learned rules", "baseline");
        return new Generation(id, 0, baseline);
    }

    private int nextGenerationNumber(Connection c, UUID systemId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COALESCE(MAX(generation_no),-1)+1 FROM project.generations WHERE system_id=?")) {
            s.setObject(1, systemId);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    private UUID insertGeneration(Connection c, UUID systemId, int number, UUID parentId,
                                  Map<String, Object> config, String changeType, String summary,
                                  String rationale, String status) throws SQLException {
        String hash = builder.hash(config);
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO project.generations (system_id,generation_no,parent_generation_id,status,config,config_hash,change_type,change_summary,rationale)
                VALUES (?,?,?,?,?::jsonb,?,?,?,?) RETURNING id
                """)) {
            s.setObject(1, systemId); s.setInt(2, number); s.setObject(3, parentId); s.setString(4, status);
            s.setString(5, json.writeValueAsString(config)); s.setString(6, hash);
            s.setString(7, changeType); s.setString(8, summary); s.setString(9, rationale);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Could not serialize generation config", e);
        }
    }

    private List<TrainingCase> cases(Connection c, UUID scenarioId, String split, String source) throws SQLException {
        String sql = """
                SELECT t.id,t.ticket_key,t.split,e.expected_category,e.expected_severity,e.expected_next_action,e.root_cause
                FROM simulator.tickets t JOIN simulator.expected_outcomes e ON e.ticket_id=t.id
                WHERE t.scenario_id=? AND t.split=?
                """ + (source == null ? "" : " AND EXISTS (SELECT 1 FROM simulator.logs x WHERE x.ticket_id=t.id AND x.subsystem=?)") + " ORDER BY t.ticket_key";
        List<TrainingCase> items = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, scenarioId); s.setString(2, split); if (source != null) s.setString(3, source);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    UUID id = r.getObject("id", UUID.class);
                    List<Signal> signals = signals(c, id);
                    CaseInput input = new CaseInput(id, r.getString("ticket_key"), r.getString("split"), signals);
                    Expected expected = new Expected(r.getString("expected_category"), r.getString("expected_severity"),
                            r.getString("expected_next_action"), r.getString("root_cause"));
                    items.add(new TrainingCase(input, expected));
                }
            }
        }
        return items;
    }

    private List<Signal> signals(Connection c, UUID ticketId) throws SQLException {
        List<Signal> items = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT id,subsystem,error_code,level,message,attributes FROM simulator.logs WHERE ticket_id=? ORDER BY occurred_at,id")) {
            s.setObject(1, ticketId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> attrs = readMap(r.getString("attributes"));
                    String eventId = attrs.get("event_id") == null ? r.getString("id") : attrs.get("event_id").toString();
                    items.add(new Signal(eventId, r.getString("subsystem"), r.getString("error_code"), r.getString("level"), r.getString("message"), attrs));
                }
            }
        }
        return items;
    }

    private Map<String, Expected> expectedMap(List<TrainingCase> cases) {
        Map<String, Expected> result = new LinkedHashMap<>();
        for (TrainingCase item : cases) result.put(item.input().ticketId().toString(), item.expected());
        return result;
    }

    private List<CaseResult> evaluate(List<TrainingCase> cases, Map<String, Expected> expectedByTicket,
                                      Map<String, Object> snapshot, boolean aiMode) {
        List<CaseResult> results = new ArrayList<>();
        for (TrainingCase item : cases) {
            Expected expected = expectedByTicket.get(item.input().ticketId().toString());
            var classification = aiMode ? aiInterpreter.classify(item.input(), snapshot)
                    : evaluator.evaluate(item.input(), expected, snapshot).output();
            double score = expected.category() != null && expected.category().equals(classification.category()) ? 1.0 : 0.0;
            results.add(new CaseResult(item.input(), expected, classification, score, null));
        }
        return results;
    }

    private List<CaseResult> evaluateCandidate(List<TrainingCase> cases, Map<String, Expected> expectedByTicket,
                                               Map<String, Object> candidateSnapshot, List<CaseResult> baseline) {
        Map<UUID, CaseResult> baselineByTicket = new LinkedHashMap<>();
        for (CaseResult item : baseline) baselineByTicket.put(item.input().ticketId(), item);
        List<CaseResult> results = new ArrayList<>();
        for (TrainingCase item : cases) {
            CaseResult baselineCase = baselineByTicket.get(item.input().ticketId());
            if (baselineCase == null) throw new IllegalStateException("Baseline output missing for validation ticket " + item.input().ticketKey());
            Expected expected = expectedByTicket.get(item.input().ticketId().toString());
            var classification = aiInterpreter.classifyCandidate(item.input(), candidateSnapshot, baselineCase.output());
            double score = expected.category() != null && expected.category().equals(classification.category()) ? 1.0 : 0.0;
            results.add(new CaseResult(item.input(), expected, classification, score, null));
        }
        return results;
    }

    private UUID datasetId(Connection c, UUID specId, String name) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM project.datasets WHERE problem_spec_id=? AND name=?")) {
            s.setObject(1, specId); s.setString(2, name);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        }
    }

    private UUID startRun(Connection c, UUID systemId, UUID generationId, UUID datasetId, UUID group) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO project.evaluation_runs (system_id,generation_id,dataset_id,run_group,status,evaluator_version,started_at) VALUES (?,?,?,?,'running','category-accuracy-v1',now()) RETURNING id")) {
            s.setObject(1, systemId); s.setObject(2, generationId); s.setObject(3, datasetId); s.setObject(4, group);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        }
    }

    private List<CaseResult> saveResults(Connection c, UUID runId, List<CaseResult> results) throws SQLException {
        List<CaseResult> persisted = new ArrayList<>();
        for (CaseResult result : results) {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO project.case_results (evaluation_run_id,case_key,simulator_ticket_id,input_snapshot,expected,output,score,input_tokens,output_tokens,latency_ms)
                    VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?) RETURNING id
                    """)) {
                s.setObject(1, runId); s.setString(2, result.input().ticketKey()); s.setObject(3, result.input().ticketId());
                s.setString(4, json.writeValueAsString(inputMap(result.input())));
                s.setString(5, json.writeValueAsString(Map.of("category", result.expected().category(), "severity", result.expected().severity(), "root_cause", result.expected().rootCause(), "next_action", result.expected().nextAction())));
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("category", result.output().category()); output.put("severity", result.output().severity());
                output.put("next_action", result.output().nextAction()); output.put("matched_rule", result.output().matchedRule());
                output.put("evidence_event_ids", result.output().evidenceEventIds()); output.put("metadata", result.output().metadata());
                s.setString(6, json.writeValueAsString(output));
                s.setDouble(7, result.score());
                Map<String, Object> metadata = result.output().metadata();
                s.setInt(8, number(metadata.get("input_tokens")));
                s.setInt(9, number(metadata.get("output_tokens")));
                s.setInt(10, number(metadata.get("latency_ms")));
                try (ResultSet r = s.executeQuery()) { r.next(); persisted.add(new CaseResult(result.input(), result.expected(), result.output(), result.score(), r.getObject(1, UUID.class))); }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new SQLException("Could not serialize case result", e); }
        }
        return persisted;
    }

    private Map<String, Object> inputMap(CaseInput input) {
        List<Map<String, Object>> signals = input.signals().stream().map(signal -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("event_id", signal.eventId()); item.put("source", signal.source());
            item.put("code", signal.code()); item.put("level", signal.level()); item.put("message", signal.message()); item.put("attributes", signal.attributes()); return item;
        }).toList();
        return Map.of("ticket_id", input.ticketId().toString(), "ticket_key", input.ticketKey(), "split", input.split(), "signals", signals);
    }

    private void finishRun(Connection c, UUID runId, double score) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                UPDATE project.evaluation_runs er SET status='completed',finished_at=now(),total_score=?,
                    total_input_tokens=(SELECT COALESCE(sum(cr.input_tokens),0)::int FROM project.case_results cr WHERE cr.evaluation_run_id=er.id),
                    total_output_tokens=(SELECT COALESCE(sum(cr.output_tokens),0)::int FROM project.case_results cr WHERE cr.evaluation_run_id=er.id)
                WHERE id=?
                """)) {
            s.setDouble(1, score); s.setObject(2, runId); s.executeUpdate();
        }
    }

    private void addModelUsage(Connection c, UUID runId, Map<String, Object> metadata) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE project.evaluation_runs SET total_input_tokens=total_input_tokens+?,total_output_tokens=total_output_tokens+? WHERE id=?")) {
            s.setInt(1, number(metadata.get("ai_input_tokens")));
            s.setInt(2, number(metadata.get("ai_output_tokens")));
            s.setObject(3, runId);
            s.executeUpdate();
        }
    }

    private void persistProposal(Connection c, UUID projectId, UUID specId, LessonProposal proposal,
                                 List<CaseResult> trainResults, List<CaseResult> persistedTrain,
                                 UUID systemId, Generation parent, UUID candidateId, UUID runGroup,
                                 io.signalloop.central.agents.AgentContracts.GateDecision decision) throws SQLException {
        UUID lessonId;
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO project.lessons (project_id,problem_spec_id,lesson_key,condition,action,scope,confidence,status,source)
                VALUES (?,?,?, ?,?,'domain',NULL,?, 'reflector')
                ON CONFLICT (project_id,lesson_key) DO UPDATE SET condition=EXCLUDED.condition,action=EXCLUDED.action,status=EXCLUDED.status RETURNING id
                """)) {
            s.setObject(1, projectId); s.setObject(2, specId); s.setString(3, proposal.key()); s.setString(4, proposal.condition());
            s.setString(5, proposal.action()); s.setString(6, decision.decision().equals("accepted") ? "accepted" : "rejected");
            try (ResultSet r = s.executeQuery()) { r.next(); lessonId = r.getObject(1, UUID.class); }
        }
        Map<UUID, UUID> resultIds = new LinkedHashMap<>();
        for (CaseResult result : persistedTrain) resultIds.put(result.input().ticketId(), result.resultId());
        for (UUID ticketId : proposal.evidenceTicketIds()) {
            UUID resultId = resultIds.get(ticketId);
            if (resultId != null) try (PreparedStatement s = c.prepareStatement("INSERT INTO project.lesson_evidence (lesson_id,case_result_id,evidence_role) VALUES (?,?,'support') ON CONFLICT DO NOTHING")) {
                s.setObject(1, lessonId); s.setObject(2, resultId); s.executeUpdate();
            }
        }
        if (decision.decision().equals("accepted")) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO project.generation_lessons (generation_id,lesson_id) SELECT ?,lesson_id FROM project.generation_lessons WHERE generation_id=? ON CONFLICT DO NOTHING")) {
                s.setObject(1, candidateId); s.setObject(2, parent.id()); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO project.generation_lessons (generation_id,lesson_id) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                s.setObject(1, candidateId); s.setObject(2, lessonId); s.executeUpdate();
            }
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE project.generations SET status=? WHERE id=?")) {
            s.setString(1, decision.decision().equals("accepted") ? "accepted" : "rejected"); s.setObject(2, candidateId); s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO project.change_decisions (system_id,parent_generation_id,candidate_generation_id,validation_run_group,baseline_mean,candidate_mean,mean_delta,lower_bound_90,margin,decision,reason)
                VALUES (?,?,?,?,?,?,?, ?,0,?,?)
                """)) {
            s.setObject(1, systemId); s.setObject(2, parent.id()); s.setObject(3, candidateId); s.setObject(4, runGroup);
            s.setDouble(5, decision.baselineScore()); s.setDouble(6, decision.candidateScore());
            s.setDouble(7, decision.candidateScore() - decision.baselineScore());
            s.setNull(8, java.sql.Types.NUMERIC);
            s.setString(9, decision.decision()); s.setString(10, decision.reason()); s.executeUpdate();
        }
    }

    private double mean(List<CaseResult> results) { return results.stream().mapToDouble(CaseResult::score).average().orElse(0.0); }

    private static int number(Object value) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return value == null ? 0 : Math.max(0, Integer.parseInt(value.toString())); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private Map<String, Object> readMap(String value) throws SQLException {
        try { return value == null ? Map.of() : json.readValue(value, MAP); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new SQLException("Invalid JSON in SignalLoop record", e); }
    }

    private record TrainingCase(CaseInput input, Expected expected) {}
    private record Generation(UUID id, int number, Map<String, Object> config) {}
}
