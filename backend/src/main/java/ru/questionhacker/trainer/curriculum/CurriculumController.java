package ru.questionhacker.trainer.curriculum;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/curriculum")
public class CurriculumController {

    private final CurriculumService curriculum;

    public CurriculumController(CurriculumService curriculum) {
        this.curriculum = curriculum;
    }

    @GetMapping("/categories")
    public List<CurriculumService.CategorySummary> categories() {
        return curriculum.categories();
    }

    @GetMapping("/categories/{code}")
    public CurriculumService.CategoryDetail category(@PathVariable String code) {
        return curriculum.category(code);
    }
}
