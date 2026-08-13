package ru.questionhacker.trainer.curriculum;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:curriculum-api;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class CurriculumControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void returnsSevenCanonicalCategorySummaries() throws Exception {
        mvc.perform(get("/api/curriculum/categories").with(user("student")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[3].code").value("BACKCASTING"))
                .andExpect(jsonPath("$[3].name").value("Backcasting"))
                .andExpect(jsonPath("$[0].operation").isNotEmpty());
    }

    @Test
    void returnsTheoryWithEvidenceLabelsSourcesAndContrasts() throws Exception {
        mvc.perform(get("/api/curriculum/categories/INVERSION").with(user("student")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("INVERSION"))
                .andExpect(jsonPath("$.formula.length()").value(4))
                .andExpect(jsonPath("$.strengthAnchors.length()").value(3))
                .andExpect(jsonPath("$.workedExample.title").isNotEmpty())
                .andExpect(jsonPath("$.workedExample.reasoningSteps.length()").value(4))
                .andExpect(jsonPath("$.workedExample.solution").isNotEmpty())
                .andExpect(jsonPath("$.workedExample.confusion.otherCategory").value("BACKCASTING"))
                .andExpect(jsonPath("$.questionTemplates.length()").value(5))
                .andExpect(jsonPath("$.quickExercise").isNotEmpty())
                .andExpect(jsonPath("$.experiment").isNotEmpty())
                .andExpect(jsonPath("$.cases.length()").value(3))
                .andExpect(jsonPath("$.cases[0].sources[0].url").isNotEmpty())
                .andExpect(jsonPath("$.sections[0].evidenceGrade").value("HEURISTIC"))
                .andExpect(jsonPath("$.sections[1].source.title").isNotEmpty())
                .andExpect(jsonPath("$.contrasts.length()").value(6));
    }

    @Test
    void backcastingWorkedExampleMovesFromObservableFutureToToday() throws Exception {
        mvc.perform(get("/api/curriculum/categories/BACKCASTING").with(user("student")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedExample.reasoningSteps[0].label").value("2030"))
                .andExpect(jsonPath("$.workedExample.reasoningSteps[1].label").value("2028"))
                .andExpect(jsonPath("$.workedExample.reasoningSteps[2].label").value("2027"))
                .andExpect(jsonPath("$.workedExample.reasoningSteps[3].label").value("Сегодня"))
                .andExpect(jsonPath("$.workedExample.hackerQuestion").value(org.hamcrest.Matchers.containsString("2030")));
    }

    @Test
    void unknownCategoryUsesProblemDetailsNotFound() throws Exception {
        mvc.perform(get("/api/curriculum/categories/UNKNOWN").with(user("student")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void curriculumRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/curriculum/categories"))
                .andExpect(status().isUnauthorized());
    }
}
