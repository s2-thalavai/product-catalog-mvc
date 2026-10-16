package com.infy.catalog.product.domain;

/** -> 404 PRODUCT_NOT_FOUND */
public class ProductNotFoundException extends CatalogException {

    public ProductNotFoundException(long id) {
        super("Product " + id + " was not found");
    }
}
