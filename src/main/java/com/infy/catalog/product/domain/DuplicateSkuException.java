package com.infy.catalog.product.domain;

/** -> 409 DUPLICATE_SKU */
public class DuplicateSkuException extends CatalogException {

    public DuplicateSkuException(String sku) {
        super("A product with SKU '" + sku + "' already exists");
    }
}
