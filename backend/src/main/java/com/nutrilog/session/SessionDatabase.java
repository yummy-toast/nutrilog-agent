package com.nutrilog.session;

import com.nutrilog.session.SessionDirectory.Snapshot;
import com.nutrilog.session.SessionDirectory.View;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 单应用实例使用的本地文件库。每条 SQL 在自动提交模式下独立完成。 */
final class SessionDatabase implements AutoCloseable {
    record Saved(String owner, View view, Snapshot snapshot) {}
    private final JsonMapper json = JsonMapper.builder().build();
    private final Connection connection;

    SessionDatabase(String url) throws SQLException {
        connection = DriverManager.getConnection(url, "sa", "");
        try (var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS conversation_session (
                    session_id VARCHAR(36) PRIMARY KEY,
                    owner_name VARCHAR(255) NOT NULL,
                    title VARCHAR(100) NOT NULL,
                    created_at VARCHAR(40) NOT NULL,
                    snapshot CLOB NOT NULL
                )
                """);
        } catch (SQLException error) {
            try { close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    List<Saved> load() throws SQLException {
        var saved = new ArrayList<Saved>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT session_id, owner_name, title, created_at, snapshot FROM conversation_session")) {
            while (rows.next()) {
                var view = new View(UUID.fromString(rows.getString(1)), rows.getString(3), Instant.parse(rows.getString(4)));
                saved.add(new Saved(rows.getString(2), view, json.readValue(rows.getString(5), Snapshot.class)));
            }
        }
        return saved;
    }

    synchronized void insert(String owner, View view, Snapshot snapshot) {
        write("INSERT INTO conversation_session (session_id, owner_name, title, created_at, snapshot) VALUES (?, ?, ?, ?, ?)",
            view.sessionId().toString(), owner, view.title(), view.createdAt().toString(), json.writeValueAsString(snapshot));
    }

    synchronized void update(UUID id, String owner, Snapshot snapshot) {
        write("UPDATE conversation_session SET snapshot = ? WHERE session_id = ? AND owner_name = ?",
            json.writeValueAsString(snapshot), id.toString(), owner);
    }

    synchronized void delete(UUID id, String owner) {
        write("DELETE FROM conversation_session WHERE session_id = ? AND owner_name = ?", id.toString(), owner);
    }

    private void write(String sql, String... values) {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw unavailable();
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话存储暂不可用");
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
