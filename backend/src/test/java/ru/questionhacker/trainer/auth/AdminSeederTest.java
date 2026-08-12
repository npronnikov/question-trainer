package ru.questionhacker.trainer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import ru.questionhacker.trainer.AppProperties;

@JdbcTest
@Import(UserAccountRepository.class)
class AdminSeederTest {

    @Autowired
    private UserAccountRepository users;

    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);

    @Test
    void migrationProvidesSystemAccountWithoutPassword() {
        UserAccountRepository.AccountRow system = users
                .findAccountByLogin("__system__")
                .orElseThrow();

        assertThat(system.systemAccount()).isTrue();
        assertThat(system.passwordHash()).isNull();
        assertThat(system.roles()).isEmpty();
    }

    @Test
    void seederCreatesAdminFromConfiguredCredentials() {
        seeder("root", "root-password-123", "root@example.test").seed();

        UserAccountRepository.AccountRow admin = users.findAccountByLogin("ROOT").orElseThrow();
        assertThat(admin.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(passwords.matches("root-password-123", admin.passwordHash())).isTrue();
    }

    @Test
    void secondRunDoesNotCreateDuplicateOrReplacePasswordHash() {
        seeder("root", "root-password-123", null).seed();
        String originalHash = users.findAccountByLogin("root").orElseThrow().passwordHash();

        seeder("ROOT", "different-password-456", null).seed();

        UserAccountRepository.AccountRow admin = users.findAccountByLogin("root").orElseThrow();
        assertThat(admin.passwordHash()).isEqualTo(originalHash);
        assertThat(passwords.matches("different-password-456", admin.passwordHash())).isFalse();
    }

    @Test
    void seederRejectsPartialAdminCredentials() {
        assertThatThrownBy(() -> seeder("root", "", null).seed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ADMIN_USERNAME")
                .hasMessageContaining("APP_ADMIN_PASSWORD");
    }

    private AdminSeeder seeder(String username, String password, String email) {
        AppProperties properties = new AppProperties(
                null,
                null,
                new AppProperties.Admin(username, password, email));
        return new AdminSeeder(users, passwords, properties);
    }
}
