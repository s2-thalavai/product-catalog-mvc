package com.infy.catalog.product.domain;

/** The client's If-Match ETag is stale. -> 412 PRECONDITION_FAILED */
public class VersionMismatchException extends CatalogException {

    public VersionMismatchException(long id) {
        super("Product " + id + " was changed since you read it");
    }
}
