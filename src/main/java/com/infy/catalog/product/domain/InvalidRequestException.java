package com.infy.catalog.product.domain;

import java.util.List;

/** Request rules that Bean Validation can't express (e.g. unknown sort field). -> 400 VALIDATION_FAILED */
public class InvalidRequestException extends CatalogException {

    private final List<String> violations;

    public InvalidRequestException(List<String> violations) {
        super("Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
