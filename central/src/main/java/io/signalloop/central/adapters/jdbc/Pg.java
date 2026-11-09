package io.signalloop.central.adapters.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class Pg {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final ObjectMapper json;

    public Pg(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    public <T> T transaction(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T value = work.apply(connection);
                connection.commit();
                return value;
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            } catch (SQLException exception) {
                connection.rollback();
                throw PgErrors.translate(exception);
            }
        } catch (SQLException exception) {
            throw PgErrors.translate(exception);
        }
    }

    public void text(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    public void uuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    public void time(PreparedStatement statement, int index, OffsetDateTime value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, value);
        }
    }

    public void json(PreparedStatement statement, int index, Map<String, Object> value) throws SQLException {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(json.writeValueAsString(value == null ? Map.of() : value));
            statement.setObject(index, object);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new SQLException("Unable to write jsonb", exception);
        }
    }

    public Map<String, Object> readJson(ResultSet rows, String column) throws SQLException {
        String raw = rows.getString(column);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new SQLException("Unable to read jsonb", exception);
        }
    }

    public UUID uuid(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, UUID.class);
    }

    public OffsetDateTime time(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class);
    }

    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }
}
