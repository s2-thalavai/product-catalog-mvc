package com.infy.catalog.product.domain;

/** Base type for expected business failures; GlobalExceptionHandler maps each subtype to a status. */
public abstract class CatalogException extends RuntimeException {

    protected CatalogException(String message) {
        super(message);
    }
}
