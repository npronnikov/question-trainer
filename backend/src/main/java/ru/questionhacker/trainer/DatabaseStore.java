package ru.questionhacker.trainer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseStore {

    private final JdbcTemplate jdbc;

    public DatabaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SessionRow createSession(String title) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var row = new SessionRow(UUID.randomUUID(), title, null, now, now);
        jdbc.update("INSERT INTO chat_session(id,title,acp_session_id,created_at,updated_at) VALUES (?,?,?,?,?)",
                row.id(), row.title(), row.acpSessionId(), row.createdAt(), row.updatedAt());
        return row;
    }

    public Optional<SessionRow> findSession(UUID id) {
        return jdbc.query("SELECT * FROM chat_session WHERE id=?", this::mapSession, id).stream().findFirst();
    }

    public List<SessionRow> listSessions() {
        return jdbc.query("SELECT * FROM chat_session ORDER BY updated_at DESC", this::mapSession);
    }

    public void touchSession(UUID id, String title) {
        jdbc.update("UPDATE chat_session SET title=?, updated_at=? WHERE id=?",
                title, OffsetDateTime.now(ZoneOffset.UTC), id);
    }

    public MessageRow addMessage(UUID sessionId, String role, String source, String content) {
        var row = new MessageRow(UUID.randomUUID(), sessionId, role, source, content,
                OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("INSERT INTO chat_message(id,session_id,role,source,content,created_at) VALUES (?,?,?,?,?,?)",
                row.id(), row.sessionId(), row.role(), row.source(), row.content(), row.createdAt());
        jdbc.update("UPDATE chat_session SET updated_at=? WHERE id=?", row.createdAt(), sessionId);
        return row;
    }

    public List<MessageRow> listMessages(UUID sessionId) {
        return jdbc.query("SELECT * FROM chat_message WHERE session_id=? ORDER BY created_at ASC",
                this::mapMessage, sessionId);
    }

    public List<MessageRow> latestMessages(UUID sessionId, int limit) {
        var reversed = jdbc.query("SELECT * FROM chat_message WHERE session_id=? ORDER BY created_at DESC LIMIT ?",
                this::mapMessage, sessionId, limit);
        return reversed.reversed();
    }

    public ScenarioRow addScenario(String situation, String category, String explanation) {
        var row = new ScenarioRow(UUID.randomUUID(), situation, category, explanation,
                OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("INSERT INTO generated_scenario(id,situation,category,explanation,created_at) VALUES (?,?,?,?,?)",
                row.id(), row.situation(), row.category(), row.explanation(), row.createdAt());
        return row;
    }

    public List<ScenarioRow> listScenarios() {
        return jdbc.query("SELECT * FROM generated_scenario ORDER BY created_at DESC", this::mapScenario);
    }

    private SessionRow mapSession(ResultSet rs, int ignored) throws SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("acp_session_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private MessageRow mapMessage(ResultSet rs, int ignored) throws SQLException {
        return new MessageRow(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("role"),
                rs.getString("source"),
                rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private ScenarioRow mapScenario(ResultSet rs, int ignored) throws SQLException {
        return new ScenarioRow(
                rs.getObject("id", UUID.class),
                rs.getString("situation"),
                rs.getString("category"),
                rs.getString("explanation"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public record SessionRow(UUID id, String title, String acpSessionId,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record MessageRow(UUID id, UUID sessionId, String role, String source,
                             String content, OffsetDateTime createdAt) {
    }

    public record ScenarioRow(UUID id, String situation, String category,
                              String explanation, OffsetDateTime createdAt) {
    }
}
