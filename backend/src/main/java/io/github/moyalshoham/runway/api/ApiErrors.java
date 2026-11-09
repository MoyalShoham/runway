package io.github.moyalshoham.runway.api;

import io.github.moyalshoham.runway.importer.CsvRejectedException;
import io.github.moyalshoham.runway.service.BusyException;
import io.github.moyalshoham.runway.service.NotFoundException;
import io.github.moyalshoham.runway.service.TooManyRequestsException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 7807 problem responses. Standard Spring MVC errors (405, 415, 400 for
 * unreadable bodies, 413 for oversized uploads) keep their status from the base class; unexpected
 * errors are logged and answered with a generic 500 that exposes nothing internal.
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CsvRejectedException.class)
    ProblemDetail csvRejected(CsvRejectedException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        p.setTitle("CSV rejected");
        p.setProperty(
                "errors",
                e.errors().stream()
                        .map(r -> Map.of("line", r.line(), "message", r.message()))
                        .toList());
        return p;
    }

    @ExceptionHandler(BusyException.class)
    ProblemDetail busy(BusyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ProblemDetail tooMany(TooManyRequestsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    /** JDK parsing errors carry raw input in their message; answer generically. */
    @ExceptionHandler(NumberFormatException.class)
    ProblemDetail badNumber(NumberFormatException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid number");
    }

    /** Domain validation (records, scenario fields, asOf range) and bean validation on parameters. */
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof IllegalArgumentException ? e.getMessage() : "Invalid request parameters";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .toList();
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
        p.setProperty("errors", fields);
        return ResponseEntity.badRequest().body(p);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Files are limited to 5 MB"));
    }
}
