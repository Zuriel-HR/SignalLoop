package io.signalloop.central.adapters.jdbc;

import io.signalloop.central.application.Commands.ProblemSpecCommand;
import io.signalloop.central.application.Commands.ProjectCommand;
import io.signalloop.central.domain.NotFoundException;
import io.signalloop.central.domain.Records.ProblemSpec;
import io.signalloop.central.domain.Records.Project;
import io.signalloop.central.ports.ProjectStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PgProjectStore implements ProjectStore {

    private final Pg pg;

    public PgProjectStore(Pg pg) {
        this.pg = pg;
    }

    @Override
    public Project insert(ProjectCommand command) {
        return pg.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO project.projects (slug, name, description)
                    VALUES (?, ?, ?)
                    RETURNING id, slug, name, description, created_at
                    """)) {
                statement.setString(1, command.slug());
                statement.setString(2, command.name());
                pg.text(statement, 3, command.description());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return project(rows);
                }
            }
        });
    }

    @Override
    public Optional<Project> find(UUID id) {
        return pg.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, slug, name, description, created_at
                    FROM project.projects
                    WHERE id = ?
                    """)) {
                statement.setObject(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(project(rows));
                }
            }
        });
    }

    @Override
    public ProblemSpec insertProblem(UUID projectId, ProblemSpecCommand command) {
        return pg.transaction(connection -> {
            if (!exists(connection, "project.projects", projectId)) {
                throw new NotFoundException("PROJECT_NOT_FOUND", "Project does not exist");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO project.problem_specs
                        (project_id, key, title, domain, description, evaluator_version)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id, project_id, key, title, domain, description, evaluator_version, created_at
                    """)) {
                statement.setObject(1, projectId);
                statement.setString(2, command.key());
                statement.setString(3, command.title());
                statement.setString(4, command.domain());
                statement.setString(5, command.description());
                statement.setString(6, command.evaluatorVersion());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return new ProblemSpec(
                            pg.uuid(rows, "id"),
                            pg.uuid(rows, "project_id"),
                            rows.getString("key"),
                            rows.getString("title"),
                            rows.getString("domain"),
                            rows.getString("description"),
                            rows.getString("evaluator_version"),
                            pg.time(rows, "created_at"));
                }
            }
        });
    }

    private Project project(ResultSet rows) throws SQLException {
        return new Project(
                pg.uuid(rows, "id"),
                rows.getString("slug"),
                rows.getString("name"),
                rows.getString("description"),
                pg.time(rows, "created_at"));
    }

    private boolean exists(java.sql.Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
