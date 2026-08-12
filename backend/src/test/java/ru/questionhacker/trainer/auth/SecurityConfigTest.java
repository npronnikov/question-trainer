package ru.questionhacker.trainer.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:security;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void systemStatusIsPublic() throws Exception {
        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk());
    }

    @Test
    void applicationApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mutationWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"user-one","password":"long-password-123"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyGenerationAndTwoStepPracticeRoutesAreGone() throws Exception {
        mvc.perform(post("/api/scenarios/generate")
                        .with(user("legacy-user")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/practice/review")
                        .with(user("legacy-user")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
