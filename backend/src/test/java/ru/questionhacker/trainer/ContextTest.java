package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
})
class ContextTest {

    @Autowired
    private DatabaseStore store;

    @BeforeEach
    void cleanDatabase() {
        store.listSessions(UserAccountRepository.SYSTEM_USER_ID).forEach(session ->
                store.deleteSession(UserAccountRepository.SYSTEM_USER_ID, session.id()));
    }

    @Test
    void contextLoads() {
    }

    @Test
    void deletingSessionAlsoDeletesItsMessages() {
        var ownerId = UserAccountRepository.SYSTEM_USER_ID;
        var session = store.createSession(ownerId, "Удаляемый диалог");
        store.addMessage(ownerId, session.id(), "USER", "USER", "Проверка");

        store.deleteSession(ownerId, session.id());

        assertThat(store.findSession(ownerId, session.id())).isEmpty();
        assertThat(store.listMessages(ownerId, session.id())).isEmpty();
    }
}
