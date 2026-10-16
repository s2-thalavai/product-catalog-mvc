package com.infy.catalog.common.error;

import java.net.URI;

/**
 * Stable, machine-readable error codes: the {@code errorCode} member of every problem.
 * Clients branch on these, never on titles or messages.
 */
public enum ErrorCode {
    PRODUCT_NOT_FOUND(404, "Product not found", "product-not-found"),
    DUPLICATE_SKU(409, "Duplicate SKU", "duplicate-sku"),
    VALIDATION_FAILED(400, "Validation failed", "validation-failed"),
    MALFORMED_REQUEST(400, "Malformed request", "malformed-request"),
    PRECONDITION_FAILED(412, "Precondition failed", "precondition-failed"),
    UNAUTHORIZED(401, "Unauthorized", "unauthorized"),
    FORBIDDEN(403, "Forbidden", "forbidden"),
    NOT_FOUND(404, "Not Found", null),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed", null),
    NOT_ACCEPTABLE(406, "Not Acceptable", null),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type", null),
    REQUEST_REJECTED(400, "Bad Request", null),
    INTERNAL_ERROR(500, "Internal Server Error", null);

    public static final String TYPE_BASE = "https://catalog.infy.com/problems/";

    private final int status;
    private final String title;
    private final String slug;

    ErrorCode(int status, String title, String slug) {
        this.status = status;
        this.title = title;
        this.slug = slug;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** API-specific problems get a documentation URI; plain HTTP errors use about:blank (RFC 9457 4.2.1). */
    public URI type() {
        return URI.create(slug == null ? "about:blank" : TYPE_BASE + slug);
    }

    /** Code for an error raised by the framework, by HTTP status. */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 412 -> PRECONDITION_FAILED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : REQUEST_REJECTED;
        };
    }
}
