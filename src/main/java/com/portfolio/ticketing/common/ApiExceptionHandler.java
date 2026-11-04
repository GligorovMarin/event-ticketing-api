package com.portfolio.ticketing.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into HTTP responses, in ONE place, always in the same shape: an RFC 9457
 * "problem details" JSON object, e.g. {@code {"title":"Conflict","status":409,"detail":"..."}}.
 *
 * <p>{@code @RestControllerAdvice} makes this class apply to every controller. Services simply
 * {@code throw new ConflictException("...")}; they never build HTTP responses themselves. The parent class
 * {@link ResponseEntityExceptionHandler} already converts Spring's own errors (malformed JSON, wrong HTTP
 * method, missing parameter ...) into problem details; we add our exceptions on top.
 *
 * <p>Connected to: the exceptions in this package, thrown by the services in auth/, event/, order/ ...
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException exception) {
        return problem(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    /** Thrown by @PreAuthorize and by our own ownership checks: logged in, but not allowed (403). */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
    }

    /**
     * Bean Validation failed (@NotBlank, @Size, @Email ... on a request record). We list every broken field
     * so a client can fix all of them in one round trip instead of discovering them one at a time.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // LinkedHashMap keeps the fields in the order they were reported.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "The request has invalid fields");
        problem.setProperty("errors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
