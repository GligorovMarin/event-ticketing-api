package com.portfolio.ticketing.common;

import jakarta.validation.ConstraintViolationException;
import com.portfolio.ticketing.ratelimit.TooManyRequestsException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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

    /** A rate limit was exceeded: 429 plus a Retry-After header telling the client how many seconds to wait. */
    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> tooManyRequests(TooManyRequestsException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()));
    }

    /**
     * We waited too long for a row lock (the lock_timeout set in OrderService), or PostgreSQL aborted us to break a
     * deadlock. Both mean "someone else is busy with the same rows right now": a 409 the client may retry.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail lockContention(PessimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "Those seats are busy right now. Please try again");
    }

    /** Two requests changed the same order at once (optimistic locking): the loser gets a 409 and may retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(OptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "The order was changed by another request. Please reload and try again");
    }

    /**
     * Bean Validation failed somewhere that is not a request body (for example a @Valid argument of a service
     * method). It is a caller error, so 400, never a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException exception) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "The request has invalid values");
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> errors.putIfAbsent(
                violation.getPropertyPath().toString(), violation.getMessage()));
        problem.setProperty("errors", errors);
        return problem;
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
