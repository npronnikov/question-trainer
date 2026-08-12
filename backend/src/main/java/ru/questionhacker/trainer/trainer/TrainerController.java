package ru.questionhacker.trainer.trainer;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping("/attempts")
    public TrainerEngine.AttemptFeedback answer(@Valid @RequestBody AnswerRequest request) {
        return trainer.answer(
                auth.requireCurrentUser().id(), request.issuanceId(),
                request.selectedCategory(), request.rationale());
    }

    public record AnswerRequest(
            @NotNull UUID issuanceId,
            @NotBlank @Size(max = 40) String selectedCategory,
            @NotBlank @Size(min = 20, max = 2000) String rationale) {
    }
}
