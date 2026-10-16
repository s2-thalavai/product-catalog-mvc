package com.infy.catalog.product.domain;

import java.math.BigDecimal;

/**
 * Partial update (JSON Merge Patch semantics): null = leave unchanged,
 * "" for description/category = clear it.
 */
public record ProductPatch(String sku, String name, String description, String category, BigDecimal price, Integer stock) {

    /** The full data after applying this patch to {@code current}. */
    public ProductData applyTo(Product current) {
        return ProductData.normalized(
                sku != null ? sku : current.getSku(),
                name != null ? name : current.getName(),
                description != null ? description : current.getDescription(),
                category != null ? category : current.getCategory(),
                price != null ? price : current.getPrice(),
                stock != null ? stock : current.getStock());
    }
}
