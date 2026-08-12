package ru.questionhacker.trainer.practice;

import java.util.UUID;

import jakarta.validation.Valid;
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
    private final AuthService auth;

    public PracticeController(PracticeAssignmentService assignments, AuthService auth) {
        this.assignments = assignments;
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

    public record AssignmentRequest(@Size(max = 40) String targetCategory) {
    }
}
