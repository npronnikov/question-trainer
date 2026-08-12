package ru.questionhacker.trainer.trainer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.questionhacker.trainer.auth.AuthService;

@RestController
@RequestMapping("/api/trainer")
public class TrainerController {

    private final TrainerEngine trainer;
    private final AuthService auth;

    public TrainerController(TrainerEngine trainer, AuthService auth) {
        this.trainer = trainer;
        this.auth = auth;
    }

    @GetMapping("/next")
    public TrainerEngine.NextCard next(
            @RequestParam(required = false) String difficulty) {
        return trainer.next(auth.requireCurrentUser().id(), difficulty);
    }
}
