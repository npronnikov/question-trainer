package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ContextTest {

    @Autowired
    private DatabaseStore store;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        store.listSessions().forEach(session -> store.deleteSession(session.id()));
    }

    @Test
    void contextLoads() {
    }

    @Test
    void deletingSessionAlsoDeletesItsMessages() throws Exception {
        var session = store.createSession("Удаляемый диалог");
        store.addMessage(session.id(), "USER", "USER", "Проверка");

        mockMvc.perform(delete("/api/chat/sessions/{sessionId}", session.id()))
                .andExpect(status().isNoContent());

        assertThat(store.findSession(session.id())).isEmpty();
        assertThat(store.listMessages(session.id())).isEmpty();
    }
}
