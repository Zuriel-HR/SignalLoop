package io.signalloop.central.agents;

import io.signalloop.central.agents.AgentContracts.CaseInput;
import io.signalloop.central.agents.AgentContracts.CaseResult;
import io.signalloop.central.agents.AgentContracts.Classification;
import io.signalloop.central.agents.AgentContracts.Expected;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EvaluatorAgent {
    private final InterpreterAgent interpreter;

    public EvaluatorAgent(InterpreterAgent interpreter) {
        this.interpreter = interpreter;
    }

    public CaseResult evaluate(CaseInput input, Expected expected, Map<String, Object> snapshot) {
        Classification output = interpreter.classify(input, snapshot);
        double score = expected.category() != null && expected.category().equals(output.category()) ? 1.0 : 0.0;
        return new CaseResult(input, expected, output, score, null);
    }

    public Batch evaluate(List<CaseInput> inputs, Map<String, Expected> expectedByTicket, Map<String, Object> snapshot) {
        List<CaseResult> results = inputs.stream()
                .map(input -> evaluate(input, expectedByTicket.get(input.ticketId().toString()), snapshot))
                .toList();
        double score = results.isEmpty() ? 0.0 : results.stream().mapToDouble(CaseResult::score).average().orElse(0.0);
        return new Batch(results, score);
    }

    public record Batch(List<CaseResult> results, double score) {}
}
