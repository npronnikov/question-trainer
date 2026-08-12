package ru.questionhacker.trainer.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserAccountRepository {

    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    private final JdbcTemplate jdbc;

    public UserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AppUser create(String username, String email, String passwordHash,
                          Set<String> roles, boolean systemAccount) {
        String cleanUsername = username.strip();
        String cleanEmail = email == null || email.isBlank() ? null : email.strip();
        Set<String> cleanRoles = new LinkedHashSet<>(roles);
        if (!ALLOWED_ROLES.containsAll(cleanRoles)) {
            throw new IllegalArgumentException("Неизвестная роль");
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO app_user(
                  id, username, normalized_username, email, normalized_email,
                  password_hash, system_account, enabled, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                id, cleanUsername, normalize(cleanUsername), cleanEmail,
                cleanEmail == null ? null : normalize(cleanEmail), passwordHash,
                systemAccount, true, now, now);

        cleanRoles.stream().sorted().forEach(role ->
                jdbc.update("INSERT INTO user_role(user_id, role) VALUES (?,?)", id, role));

        return findPublicById(id).orElseThrow();
    }

    public Optional<AppUser> findPublicByLogin(String login) {
        return findAccountByLogin(login).map(AccountRow::toPublicUser);
    }

    public Optional<AppUser> findPublicById(UUID id) {
        return findAccountById(id).map(AccountRow::toPublicUser);
    }

    Optional<AccountRow> findAccountByLogin(String login) {
        if (login == null || login.isBlank()) return Optional.empty();
        String normalized = normalize(login);
        return jdbc.query("""
                SELECT * FROM app_user
                WHERE normalized_username=? OR normalized_email=?
                """, this::mapAccount, normalized, normalized).stream().findFirst();
    }

    Optional<AccountRow> findAccountById(UUID id) {
        return jdbc.query("SELECT * FROM app_user WHERE id=?", this::mapAccount, id)
                .stream().findFirst();
    }

    public boolean usernameExists(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE normalized_username=?",
                Integer.class, normalize(username));
        return count != null && count > 0;
    }

    public boolean emailExists(String email) {
        if (email == null || email.isBlank()) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE normalized_email=?",
                Integer.class, normalize(email));
        return count != null && count > 0;
    }

    public void addRole(UUID userId, String role) {
        if (!ALLOWED_ROLES.contains(role)) throw new IllegalArgumentException("Неизвестная роль");
        jdbc.update("INSERT INTO user_role(user_id, role) VALUES (?,?)", userId, role);
    }

    private AccountRow mapAccount(ResultSet rs, int ignored) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<String> roles = jdbc.queryForList(
                "SELECT role FROM user_role WHERE user_id=? ORDER BY role", String.class, id);
        return new AccountRow(
                id,
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("password_hash"),
                Set.copyOf(roles),
                rs.getBoolean("system_account"),
                rs.getBoolean("enabled"));
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    record AccountRow(UUID id, String username, String email, String passwordHash,
                      Set<String> roles, boolean systemAccount, boolean enabled) {
        AppUser toPublicUser() {
            return new AppUser(id, username, email, roles, systemAccount, enabled);
        }
    }
}
