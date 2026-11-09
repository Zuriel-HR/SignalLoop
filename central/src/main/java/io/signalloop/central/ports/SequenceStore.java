package io.signalloop.central.ports;

import io.signalloop.central.application.Commands.AtmCommand;
import io.signalloop.central.application.Commands.LogCommand;
import io.signalloop.central.application.Commands.OutcomeCommand;
import io.signalloop.central.application.Commands.ScenarioCommand;
import io.signalloop.central.application.Commands.TicketCommand;
import io.signalloop.central.domain.Records.Atm;
import io.signalloop.central.domain.Records.ExpectedOutcome;
import io.signalloop.central.domain.Records.Scenario;
import io.signalloop.central.domain.Records.SignalLog;
import io.signalloop.central.domain.Records.Ticket;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SequenceStore {

    Scenario insertScenario(ScenarioCommand command);

    Optional<Scenario> findScenario(UUID id);

    Atm insertAtm(UUID scenarioId, AtmCommand command);

    Ticket insertTicket(UUID scenarioId, TicketCommand command);

    SignalLog insertLog(UUID scenarioId, LogCommand command);

    List<SignalLog> logsForAtm(UUID atmId);

    ExpectedOutcome insertOutcome(UUID ticketId, OutcomeCommand command);
}
