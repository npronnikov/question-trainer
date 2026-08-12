package ru.questionhacker.trainer.auth;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import ru.questionhacker.trainer.AppProperties;

@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final AppProperties properties;

    public AdminSeeder(UserAccountRepository users, PasswordEncoder passwords,
                       AppProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        AppProperties.Admin configured = properties.admin();
        String username = clean(configured == null ? null : configured.username());
        String password = clean(configured == null ? null : configured.password());
        String email = clean(configured == null ? null : configured.email());

        boolean usernameMissing = username == null;
        boolean passwordMissing = password == null;
        if (usernameMissing && passwordMissing && email == null) return;
        if (usernameMissing || passwordMissing) {
            throw new IllegalStateException(
                    "APP_ADMIN_USERNAME и APP_ADMIN_PASSWORD должны быть заданы вместе");
        }
        if (password.length() < 12) {
            throw new IllegalStateException("APP_ADMIN_PASSWORD должен содержать не менее 12 символов");
        }
        if (users.findPublicByLogin(username).isPresent()) {
            log.info("Bootstrap admin '{}' уже существует; пароль и роли не изменены", username);
            return;
        }

        users.create(username, email, passwords.encode(password), Set.of("USER", "ADMIN"), false);
        log.info("Создан bootstrap admin '{}'", username);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
