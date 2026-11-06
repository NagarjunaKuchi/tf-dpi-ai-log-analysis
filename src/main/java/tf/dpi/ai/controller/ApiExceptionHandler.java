package tf.dpi.ai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tf.dpi.ai.exception.CapacityExceededException;
import tf.dpi.ai.exception.ConflictException;
import tf.dpi.ai.exception.DependencyUnavailableException;
import tf.dpi.ai.exception.NotFoundException;
import tf.dpi.ai.masking.MaskingException;

/** Maps failures to RFC 9457 problem details. Messages are fixed strings: no request content, no internals. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        return pd(HttpStatus.BAD_REQUEST, "Request validation failed");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ProblemDetail unreadable(Exception e) {
        return pd(HttpStatus.BAD_REQUEST, "Malformed request");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return pd(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return pd(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CapacityExceededException.class)
    ProblemDetail capacity(CapacityExceededException e) {
        return pd(HttpStatus.SERVICE_UNAVAILABLE, "Service is at capacity; retry later");
    }

    @ExceptionHandler(MaskingException.class)
    ProblemDetail masking(MaskingException e) {
        return pd(HttpStatus.UNPROCESSABLE_ENTITY, "Content could not be safely sanitised and was rejected");
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ProblemDetail dependency(DependencyUnavailableException e) {
        log.warn("dependency unavailable: {}", e.getClass().getSimpleName());
        return pd(HttpStatus.SERVICE_UNAVAILABLE, "A required dependency is unavailable");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error: {}", e.getClass().getSimpleName());
        return pd(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private static ProblemDetail pd(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
