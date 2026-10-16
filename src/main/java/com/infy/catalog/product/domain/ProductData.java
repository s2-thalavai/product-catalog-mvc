package com.infy.catalog.product.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Writable product fields after normalization: trimmed strings, upper-case SKU, blank optional
 * strings become null, price with exactly 2 decimals. Input is validated (Bean Validation)
 * before it gets here.
 */
public record ProductData(String sku, String name, String description, String category, BigDecimal price, int stock) {

    public static ProductData normalized(String sku, String name, String description, String category,
                                         BigDecimal price, int stock) {
        return new ProductData(
                sku == null ? null : sku.trim().toUpperCase(Locale.ROOT),
                name == null ? null : name.trim(),
                blankToNull(description),
                blankToNull(category),
                price == null ? null : price.setScale(2, RoundingMode.HALF_UP),
                stock);
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
