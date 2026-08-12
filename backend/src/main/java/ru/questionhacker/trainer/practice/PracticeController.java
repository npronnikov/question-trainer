package ru.questionhacker.trainer.practice;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.questionhacker.trainer.auth.AuthService;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeAssignmentService assignments;
    private final PracticeAssessmentService assessments;
    private final AuthService auth;

    public PracticeController(PracticeAssignmentService assignments,
                              PracticeAssessmentService assessments,
                              AuthService auth) {
        this.assignments = assignments;
        this.assessments = assessments;
        this.auth = auth;
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeAssignmentService.AssignmentView createAssignment(
            @Valid @RequestBody(required = false) AssignmentRequest request) {
        return assignments.create(auth.requireCurrentUser().id(),
                request == null ? null : request.targetCategory());
    }

    @GetMapping("/assignments/{assignmentId}")
    public PracticeAssignmentService.AssignmentView assignment(@PathVariable UUID assignmentId) {
        return assignments.get(auth.requireCurrentUser().id(), assignmentId);
    }

    @PostMapping("/attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PracticeAssessmentService.AttemptView submitAttempt(@Valid @RequestBody AttemptRequest request) {
        return assessments.submit(auth.requireCurrentUser().id(),
                new PracticeAssessmentService.Submission(
                        request.assignmentId(), request.question(), request.answer(),
                        request.reasoning(), request.solution(), request.model(),
                        request.idempotencyKey()));
    }

    @GetMapping("/attempts/{attemptId}")
    public PracticeAssessmentService.AttemptView attempt(@PathVariable UUID attemptId) {
        return assessments.get(auth.requireCurrentUser().id(), attemptId);
    }

    public record AssignmentRequest(@Size(max = 40) String targetCategory) {
    }

    public record AttemptRequest(
            @NotNull UUID assignmentId,
            @NotBlank @Size(max = 1800) String question,
            @NotBlank @Size(max = 3000) String answer,
            @NotBlank @Size(max = 5000) String reasoning,
            @NotBlank @Size(max = 3000) String solution,
            @Size(max = 120) String model,
            @Size(max = 100) String idempotencyKey) {
    }
}
