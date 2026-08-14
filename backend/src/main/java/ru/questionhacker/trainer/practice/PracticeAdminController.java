package ru.questionhacker.trainer.practice;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/practice")
public class PracticeAdminController {

    private final PracticeAdministrationService administration;

    public PracticeAdminController(PracticeAdministrationService administration) {
        this.administration = administration;
    }

    @DeleteMapping("/cycles")
    public ClearResult clearCycles() {
        return new ClearResult(administration.clearAllCycles());
    }

    public record ClearResult(int deletedCycles) {
    }
}
