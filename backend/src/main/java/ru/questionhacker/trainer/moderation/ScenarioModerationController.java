package ru.questionhacker.trainer.moderation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.questionhacker.trainer.auth.AuthService;

@RestController
@RequestMapping("/api/admin/scenario-candidates")
public class ScenarioModerationController {

    private final ScenarioModerationService moderation;
    private final AuthService auth;

    public ScenarioModerationController(ScenarioModerationService moderation, AuthService auth) {
        this.moderation = moderation;
        this.auth = auth;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<ScenarioModerationService.CandidateView> generate(
            @Valid @RequestBody GenerateRequest request) {
        return moderation.generate(auth.requireCurrentUser().id(), request.target(), request.model());
    }

    @GetMapping
    public List<ScenarioModerationService.CandidateView> list(
            @RequestParam(required = false) String status) {
        return moderation.list(status);
    }

    @GetMapping("/{candidateId}")
    public ScenarioModerationService.CandidateView get(@PathVariable UUID candidateId) {
        return moderation.get(candidateId);
    }

    @PostMapping("/{candidateId}/approve")
    public ScenarioModerationService.CandidateView approve(
            @PathVariable UUID candidateId,
            @Valid @RequestBody VersionRequest request) {
        return moderation.approve(auth.requireCurrentUser().id(), candidateId, request.expectedVersion());
    }

    @PostMapping("/{candidateId}/reject")
    public ScenarioModerationService.CandidateView reject(
            @PathVariable UUID candidateId,
            @Valid @RequestBody RejectRequest request) {
        return moderation.reject(auth.requireCurrentUser().id(), candidateId,
                request.expectedVersion(), request.reason(), request.comment());
    }

    @PutMapping("/{candidateId}")
    public ScenarioModerationService.CandidateView edit(
            @PathVariable UUID candidateId,
            @Valid @RequestBody EditRequest request) {
        return moderation.edit(auth.requireCurrentUser().id(), candidateId,
                request.expectedVersion(), request.draft());
    }

    public record GenerateRequest(
            @NotBlank @Size(max = 16) String target,
            @Size(max = 120) String model) {
    }

    public record VersionRequest(@Min(0) int expectedVersion) {
    }

    public record RejectRequest(
            @Min(0) int expectedVersion,
            @NotBlank @Size(max = 40) String reason,
            @Size(max = 1000) String comment) {
    }

    public record EditRequest(
            @Min(0) int expectedVersion,
            @NotNull @Valid ScenarioDraft draft) {
    }
}
