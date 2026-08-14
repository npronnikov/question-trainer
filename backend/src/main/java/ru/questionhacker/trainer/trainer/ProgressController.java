package ru.questionhacker.trainer.trainer;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.questionhacker.trainer.auth.AuthService;

@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ProgressService progress;
    private final AuthService auth;

    public ProgressController(ProgressService progress, AuthService auth) {
        this.progress = progress;
        this.auth = auth;
    }

    @GetMapping
    public ProgressService.ProgressView progress() {
        return progress.progress(auth.requireCurrentUser().id());
    }

    @DeleteMapping
    public ProgressService.ProgressView reset() {
        return progress.reset(auth.requireCurrentUser().id());
    }
}
