package ru.questionhacker.trainer.practice;

import org.springframework.http.HttpStatus;

public final class PracticeAssignmentUnavailableException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private PracticeAssignmentUnavailableException(
            HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static PracticeAssignmentUnavailableException exhausted() {
        return new PracticeAssignmentUnavailableException(
                HttpStatus.NOT_FOUND,
                "PRACTICE_CATALOG_EXHAUSTED",
                "Вы прошли все доступные ситуации. Дождитесь, пока администратор добавит новые.");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
