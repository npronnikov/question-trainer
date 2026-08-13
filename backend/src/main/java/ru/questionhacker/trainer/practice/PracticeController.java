package ru.questionhacker.trainer.practice;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ru.questionhacker.trainer.auth.AuthService;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeAssignmentService assignments;
    private final PracticeAssessmentService assessments;
    private final PracticeCycleService cycles;
    private final AuthService auth;

    public PracticeController(PracticeAssignmentService assignments,
                              PracticeAssessmentService assessments,
                              PracticeCycleService cycles,
                              AuthService auth) {
        this.assignments = assignments;
        this.assessments = assessments;
        this.cycles = cycles;
        this.auth = auth;
    }

    @GetMapping("/cycles")
    public java.util.List<PracticeCycleService.CycleSummary> cycles() {
        return cycles.list(auth.requireCurrentUser().id());
    }

    @GetMapping("/cycles/{assignmentId}")
    public PracticeCycleService.CycleView cycle(@PathVariable UUID assignmentId) {
        return cycles.get(auth.requireCurrentUser().id(), assignmentId);
    }

    @PutMapping("/cycles/{assignmentId}/draft")
    public PracticeCycleService.DraftView saveDraft(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody DraftRequest request) {
        return cycles.saveDraft(auth.requireCurrentUser().id(), assignmentId,
                new PracticeCycleService.DraftInput(
                        request.baseAttemptId(), request.question(), request.answer(),
                        request.reasoning(), request.solution()));
    }

    @GetMapping("/examples/random")
    public PracticeCycleService.ExampleView randomExample() {
        auth.requireCurrentUser();
        return cycles.randomExample();
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeAssignmentService.AssignmentView createAssignment(
            @Valid @RequestBody(required = false) AssignmentRequest request) {
        return assignments.create(auth.requireCurrentUser().id());
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

    @PostMapping("/attempts/{attemptId}/revisions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PracticeAssessmentService.AttemptView revise(
            @PathVariable UUID attemptId,
            @Valid @RequestBody RevisionRequest request) {
        return assessments.revise(auth.requireCurrentUser().id(), attemptId,
                new PracticeAssessmentService.Revision(
                        request.question(), request.answer(), request.reasoning(),
                        request.solution(), request.model(), request.idempotencyKey()));
    }

    @GetMapping(value = "/attempts/{attemptId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID attemptId) {
        return assessments.events(auth.requireCurrentUser().id(), attemptId);
    }

    public record AssignmentRequest(@Null String targetCategory) {
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

    public record RevisionRequest(
            @Size(max = 1800) String question,
            @Size(max = 3000) String answer,
            @Size(max = 5000) String reasoning,
            @Size(max = 3000) String solution,
            @Size(max = 120) String model,
            @Size(max = 100) String idempotencyKey) {
    }

    public record DraftRequest(
            UUID baseAttemptId,
            @NotNull @Size(max = 1800) String question,
            @NotNull @Size(max = 3000) String answer,
            @NotNull @Size(max = 5000) String reasoning,
            @NotNull @Size(max = 3000) String solution) {
    }
}
