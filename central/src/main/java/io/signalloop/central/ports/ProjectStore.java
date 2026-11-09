package io.signalloop.central.ports;

import io.signalloop.central.application.Commands.ProblemSpecCommand;
import io.signalloop.central.application.Commands.ProjectCommand;
import io.signalloop.central.domain.Records.ProblemSpec;
import io.signalloop.central.domain.Records.Project;

import java.util.Optional;
import java.util.UUID;

public interface ProjectStore {

    Project insert(ProjectCommand command);

    Optional<Project> find(UUID id);

    ProblemSpec insertProblem(UUID projectId, ProblemSpecCommand command);
}
