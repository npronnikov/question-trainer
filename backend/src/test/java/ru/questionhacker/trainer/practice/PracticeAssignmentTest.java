package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "spring.datasource.url=jdbc:h2:mem:practice-assignment;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PracticeAssignmentTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    private AppUser alice;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM practice_assessment");
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM practice_assignment");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("practice-alice", null, "$2a$alice", Set.of("USER"), false);
        users.create("practice-bob", null, "$2a$bob", Set.of("USER"), false);
    }

    @Test
    void issuesServerOwnedAssignmentForRequestedCategory() throws Exception {
        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user("practice-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetCategory\":\"INVERSION\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").isNotEmpty())
                .andExpect(jsonPath("$.domain").isNotEmpty())
                .andExpect(jsonPath("$.situation").isNotEmpty())
                .andExpect(jsonPath("$.targetCategory.code").value("INVERSION"))
                .andExpect(jsonPath("$.targetCategory.name").value("Инверсия"))
                .andExpect(jsonPath("$.targetCategory.guidance").isNotEmpty())
                .andExpect(jsonPath("$.correctCategory").doesNotExist())
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\\\"assignmentId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(jdbc.queryForObject(
                "SELECT owner_id FROM practice_assignment WHERE id=?",
                java.util.UUID.class, java.util.UUID.fromString(id))).isEqualTo(alice.id());
    }

    @Test
    void assignmentReadIsOwnerOnlyAndUnknownCategoryIsRejected() throws Exception {
        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user("practice-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = response.replaceAll(".*\\\"assignmentId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/practice/assignments/{id}", id).with(user("practice-bob")))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/practice/assignments")
                        .with(user("practice-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetCategory\":\"FUTURISM\"}"))
                .andExpect(status().isBadRequest());
    }
}
