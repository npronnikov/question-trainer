package ru.questionhacker.trainer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

@JdbcTest
@Import(UserAccountRepository.class)
class UserAccountRepositoryTest {

    @Autowired
    private UserAccountRepository users;

    @Test
    void findsUserByNormalizedLoginWithRoles() {
        AppUser created = users.create(
                "Nick", "nick@example.test", "$2a$hash", Set.of("USER"), false);

        AppUser loaded = users.findPublicByLogin(" NICK ").orElseThrow();

        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.username()).isEqualTo("Nick");
        assertThat(loaded.roles()).containsExactly("USER");
    }

    @Test
    void rejectsDuplicateNormalizedUsername() {
        users.create("Nick", null, "$2a$one", Set.of("USER"), false);

        assertThatThrownBy(() ->
                users.create("nick", null, "$2a$two", Set.of("USER"), false))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
