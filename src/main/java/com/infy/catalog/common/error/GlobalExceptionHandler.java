package com.infy.catalog.common.error;

import com.infy.catalog.common.web.CorrelationIdFilter;
import com.infy.catalog.product.domain.CatalogException;
import com.infy.catalog.product.domain.DuplicateSkuException;
import com.infy.catalog.product.domain.InvalidRequestException;
import com.infy.catalog.product.domain.ProductNotFoundException;
import com.infy.catalog.product.domain.VersionMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

/**
 * The one place that turns exceptions into RFC 9457 problem details (checklist: a single
 * {@code @RestControllerAdvice}). Every problem carries {@code errorCode} and {@code traceId};
 * none carries stack traces, SQL or class names. The body is rendered in the client's format:
 * application/problem+json, application/problem+xml, HTML or text.
 *
 * Extends ResponseEntityExceptionHandler so Spring MVC's own errors (404 route, 405, 406, 415,
 * unreadable body, type mismatch...) get the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------- business rules

    @ExceptionHandler(CatalogException.class)
    ResponseEntity<Object> handleCatalog(CatalogException ex, WebRequest request) {
        if (ex instanceof ProductNotFoundException) {
            return problem(ErrorCode.PRODUCT_NOT_FOUND, ex.getMessage(), List.of(), request);
        }
        if (ex instanceof DuplicateSkuException) {
            return problem(ErrorCode.DUPLICATE_SKU, ex.getMessage(), List.of(), request);
        }
        if (ex instanceof VersionMismatchException) {
            return problem(ErrorCode.PRECONDITION_FAILED, preconditionDetail(), List.of(), request);
        }
        if (ex instanceof InvalidRequestException invalid) {
            return problem(ErrorCode.VALIDATION_FAILED, validationDetail(invalid.violations().size()),
                    invalid.violations(), request);
        }
        return handleUnexpected(ex, request);
    }

    /** Lost a race on the JPA @Version check between our If-Match check and the flush. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, WebRequest request) {
        return problem(ErrorCode.PRECONDITION_FAILED, preconditionDetail(), List.of(), request);
    }

    // ---------------------------------------------------------------- security (delegated by ProblemAuthenticationHandlers)

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        boolean invalidToken = ex instanceof InvalidBearerTokenException;
        ProblemDetail problem = build(ErrorCode.UNAUTHORIZED, invalidToken
                ? "The bearer token is invalid or expired."
                : "This operation needs a bearer token with scope catalog.write.", List.of(), request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer")
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(ErrorCode.FORBIDDEN, "Your token lacks the scope catalog.write.", List.of(), request);
    }

    // ---------------------------------------------------------------- anything else

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(ErrorCode.INTERNAL_ERROR, "Unexpected server error. Quote the traceId when reporting it.",
                List.of(), request);
    }

    // ---------------------------------------------------------------- Spring MVC errors

    /** @Valid body or query-parameter object failed: list every problem at once. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .sorted()
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, validationDetail(errors.size()), errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<String> errors = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .sorted()
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, validationDetail(errors.size()), errors, request);
    }

    /** All other framework errors: keep Spring's status, add our members, use safe details. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ErrorCode code = ErrorCode.forStatus(statusCode.value());
            enrich(problem, code, request);
            String safeDetail = frameworkDetail(code);
            if (safeDetail != null) {
                problem.setDetail(safeDetail);
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<Object> problem(ErrorCode code, String detail, List<String> errors, WebRequest request) {
        return ResponseEntity.status(code.status()).body(build(code, detail, errors, request));
    }

    static ProblemDetail build(ErrorCode code, String detail, List<String> errors, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        enrich(problem, code, request);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    static void enrich(ProblemDetail problem, ErrorCode code, WebRequest request) {
        problem.setType(code.type());
        if (problem.getTitle() == null || !"about:blank".equals(code.type().toString())) {
            problem.setTitle(code.title());
        }
        problem.setProperty("errorCode", code.name());
        problem.setProperty("traceId", CorrelationIdFilter.currentTraceId());
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
    }

    /** "name is required"; type mismatches never echo Java types. */
    static String describe(ObjectError error) {
        if (error instanceof FieldError field) {
            if (field.isBindingFailure()) {
                return field.getField() + " has an invalid value";
            }
            if ("AssertTrue".equals(field.getCode())) {
                return field.getDefaultMessage();   // cross-field rule: the message says it all
            }
            return field.getField() + " " + field.getDefaultMessage();
        }
        return error.getDefaultMessage();
    }

    static String frameworkDetail(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> "No resource at this path. Start at /api/v1/products or /swagger-ui.html.";
            case METHOD_NOT_ALLOWED -> "This HTTP method is not supported here; see the Allow header.";
            case NOT_ACCEPTABLE -> "Cannot produce the requested media type. Supported: application/json, "
                    + "application/xml, text/html, text/plain (or ?format=json|xml|html|text).";
            case UNSUPPORTED_MEDIA_TYPE -> "Send the body as application/json or application/xml "
                    + "(PATCH also accepts application/merge-patch+json).";
            case MALFORMED_REQUEST -> "The request could not be read. Check the body syntax, value types and Content-Type.";
            default -> null;
        };
    }

    private static String validationDetail(int count) {
        return "The request has " + count + " invalid field(s); see errors.";
    }

    private static String preconditionDetail() {
        return "The product changed since you read it (ETag mismatch). GET it again and retry with the new ETag in If-Match.";
    }
}
