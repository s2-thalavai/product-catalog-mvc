package com.infy.catalog.product.domain;

import java.math.BigDecimal;

/** Optional search filters; null = not applied. Strings are already trimmed (blank -> null). */
public record ProductFilter(String keyword, String category, BigDecimal minPrice, BigDecimal maxPrice, boolean inStockOnly) {

    public static ProductFilter of(String keyword, String category, BigDecimal minPrice, BigDecimal maxPrice, Boolean inStock) {
        return new ProductFilter(ProductData.blankToNull(keyword), ProductData.blankToNull(category),
                minPrice, maxPrice, Boolean.TRUE.equals(inStock));
    }

    public static ProductFilter none() {
        return new ProductFilter(null, null, null, null, false);
    }
}
