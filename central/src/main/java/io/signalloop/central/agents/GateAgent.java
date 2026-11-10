package io.signalloop.central.agents;

import io.signalloop.central.agents.AgentContracts.CaseResult;
import io.signalloop.central.agents.AgentContracts.GateDecision;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GateAgent {

    public GateDecision decide(List<CaseResult> baseline, List<CaseResult> candidate) {
        if (baseline.isEmpty() || candidate.isEmpty()) {
            return new GateDecision("inconclusive", mean(baseline), mean(candidate), 0, 0,
                    "Se requieren casos de validación tanto para la versión base como para la candidata.");
        }
        Map<String, CaseResult> baselineByTicket = index(baseline);
        Map<String, CaseResult> candidateByTicket = index(candidate);
        if (!baselineByTicket.keySet().equals(candidateByTicket.keySet())) {
            return new GateDecision("inconclusive", mean(baseline), mean(candidate), 0, 0,
                    "La candidata y la base no evaluaron el mismo conjunto de validación.");
        }

        int improvements = 0;
        int regressions = 0;
        for (String ticketKey : baselineByTicket.keySet()) {
            double before = baselineByTicket.get(ticketKey).score();
            double after = candidateByTicket.get(ticketKey).score();
            if (after > before) improvements++;
            if (after < before) regressions++;
        }
        String decision = improvements > 0 && regressions == 0 ? "accepted" : "rejected";
        String reason = decision.equals("accepted")
                ? "La candidata mejora casos de validación y no regresa ningún caso previamente correcto."
                : "La candidata no mejora la validación o provoca regresiones.";
        return new GateDecision(decision, mean(baseline), mean(candidate), improvements, regressions, reason);
    }

    private static Map<String, CaseResult> index(List<CaseResult> results) {
        Map<String, CaseResult> indexed = new HashMap<>();
        results.forEach(result -> indexed.put(result.input().ticketKey(), result));
        return indexed;
    }

    private static double mean(List<CaseResult> results) {
        return results.isEmpty() ? 0.0 : results.stream().mapToDouble(CaseResult::score).average().orElse(0.0);
    }
}
