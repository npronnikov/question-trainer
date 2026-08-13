package ru.questionhacker.trainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ru.questionhacker.trainer.practice.PracticeAssignmentUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException error) {
        String detail = error.getReason() == null ? error.getMessage() : error.getReason();
        return problem(error.getStatusCode().value(), detail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException error) {
        Map<String, String> fields = new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors().forEach(field ->
                fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        ProblemDetail result = problem(HttpStatus.BAD_REQUEST.value(), "Проверьте поля запроса");
        result.setProperty("errors", fields);
        return result;
    }

    @ExceptionHandler(PracticeAssignmentUnavailableException.class)
    ProblemDetail practiceUnavailable(PracticeAssignmentUnavailableException error) {
        ProblemDetail result = problem(error.status().value(), error.getMessage());
        result.setProperty("code", error.code());
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException error) {
        return problem(HttpStatus.BAD_REQUEST.value(), "Некорректный запрос: " + error.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail notFound(NoResourceFoundException error) {
        return problem(HttpStatus.NOT_FOUND.value(), "Маршрут не найден");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail generic(Exception error) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Внутренняя ошибка сервера");
    }

    private ProblemDetail problem(int status, String detail) {
        ProblemDetail result = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        HttpStatus resolved = HttpStatus.resolve(status);
        result.setTitle(resolved == null ? "Ошибка" : resolved.getReasonPhrase());
        result.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        return result;
    }
}
