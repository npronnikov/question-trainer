package ru.questionhacker.trainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import ru.questionhacker.trainer.auth.AppUser;
import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ownership;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ChatOwnershipTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseStore store;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private RunStreamRegistry streams;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_session");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("alice", null, "$2a$alice", Set.of("USER"), false);
        bob = users.create("bob", null, "$2a$bob", Set.of("USER"), false);
    }

    @Test
    void userListsOnlyOwnSessions() throws Exception {
        DatabaseStore.SessionRow own = store.createSession(alice.id(), "Alice session");
        store.createSession(bob.id(), "Bob session");

        mvc.perform(get("/api/chat/sessions").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(own.id().toString()));
    }

    @Test
    void userCannotReadAnotherUsersMessages() throws Exception {
        DatabaseStore.SessionRow foreign = store.createSession(bob.id(), "Bob session");

        mvc.perform(get("/api/chat/sessions/{id}/messages", foreign.id()).with(user("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCannotPostToAnotherUsersSession() throws Exception {
        DatabaseStore.SessionRow foreign = store.createSession(bob.id(), "Bob session");

        mvc.perform(post("/api/chat/sessions/{id}/messages", foreign.id())
                        .with(user("alice"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Это не мой диалог"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCannotDeleteAnotherUsersSession() throws Exception {
        DatabaseStore.SessionRow foreign = store.createSession(bob.id(), "Bob session");

        mvc.perform(delete("/api/chat/sessions/{id}", foreign.id())
                        .with(user("alice"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerRenamesSessionWithTrimmedTitle() throws Exception {
        DatabaseStore.SessionRow own = store.createSession(alice.id(), "Новый диалог");

        mvc.perform(patch("/api/chat/sessions/{id}", own.id())
                        .with(user("alice"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  Новый заголовок  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Новый заголовок"));
    }

    @Test
    void renameRejectsBlankAndOversizedTitles() throws Exception {
        DatabaseStore.SessionRow own = store.createSession(alice.id(), "Новый диалог");

        mvc.perform(patch("/api/chat/sessions/{id}", own.id())
                        .with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/chat/sessions/{id}", own.id())
                        .with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "x".repeat(181) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userCannotRenameAnotherUsersSession() throws Exception {
        DatabaseStore.SessionRow foreign = store.createSession(bob.id(), "Bob session");

        mvc.perform(patch("/api/chat/sessions/{id}", foreign.id())
                        .with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Украденное имя\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void firstMessageCreatesUnicodeSafeThirtyCodePointTitleWithSuffix() throws Exception {
        DatabaseStore.SessionRow own = store.createSession(alice.id(), "Новый диалог");
        String message = "😀".repeat(31);

        mvc.perform(post("/api/chat/sessions/{id}/messages", own.id())
                        .with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + message + "\"}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(store.findSession(alice.id(), own.id()).orElseThrow().title())
                .isEqualTo("😀".repeat(30) + "...");
    }

    @Test
    void firstMessageNormalizesWhitespaceAndManualTitleIsPreserved() throws Exception {
        DatabaseStore.SessionRow automatic = store.createSession(alice.id(), "Новый диалог");
        DatabaseStore.SessionRow manual = store.createSession(alice.id(), "Моё название");

        mvc.perform(post("/api/chat/sessions/{id}/messages", automatic.id())
                        .with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  Один   два\\nтри  \"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chat/sessions/{id}/messages", manual.id())
                        .with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Первое сообщение\"}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(store.findSession(alice.id(), automatic.id()).orElseThrow().title())
                .isEqualTo("Один два три...");
        org.assertj.core.api.Assertions.assertThat(store.findSession(alice.id(), manual.id()).orElseThrow().title())
                .isEqualTo("Моё название");
    }

    @Test
    void createdSessionBelongsToAuthenticatedUser() throws Exception {
        mvc.perform(post("/api/chat/sessions")
                        .with(user("alice"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Новый личный диалог"}
                                """))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(store.listSessions(alice.id())).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(store.listSessions(bob.id())).isEmpty();
    }

    @Test
    void userCannotSubscribeToAnotherUsersRun() throws Exception {
        var runId = streams.create(alice.id());

        mvc.perform(get("/api/chat/runs/{runId}/events", runId).with(user("bob")))
                .andExpect(status().isNotFound());
    }
}
