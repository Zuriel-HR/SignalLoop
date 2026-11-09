package io.signalloop.central.application;

import io.signalloop.central.application.Commands.ProblemSpecCommand;
import io.signalloop.central.application.Commands.ProjectCommand;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.NotFoundException;
import io.signalloop.central.domain.Records.ProblemSpec;
import io.signalloop.central.domain.Records.Project;
import io.signalloop.central.ports.ProjectStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {

    private final ProjectStore projects;

    public ProjectService(ProjectStore projects) {
        this.projects = projects;
    }

    public Project create(ProjectCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        String slug = CommandChecks.required(command.slug(), "slug", violations);
        String name = CommandChecks.required(command.name(), "name", violations);
        CommandChecks.require(violations);
        return projects.insert(new ProjectCommand(slug, name, CommandChecks.optional(command.description())));
    }

    public Project get(UUID id) {
        return projects.find(id).orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project does not exist"));
    }

    public ProblemSpec addProblem(UUID projectId, ProblemSpecCommand command) {
        List<FieldViolation> violations = CommandChecks.start();
        String key = CommandChecks.required(command.key(), "key", violations);
        String title = CommandChecks.required(command.title(), "title", violations);
        String domain = CommandChecks.required(command.domain(), "domain", violations);
        String description = CommandChecks.required(command.description(), "description", violations);
        String evaluator = CommandChecks.required(command.evaluatorVersion(), "evaluator_version", violations);
        CommandChecks.require(violations);
        return projects.insertProblem(projectId, new ProblemSpecCommand(key, title, domain, description, evaluator));
    }
}
