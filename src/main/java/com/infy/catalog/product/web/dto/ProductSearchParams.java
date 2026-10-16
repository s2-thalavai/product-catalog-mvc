package com.infy.catalog.product.web.dto;

import com.infy.catalog.product.domain.ProductFilter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Search filters of GET /api/v1/products (bound from query parameters). Paging and sorting come
 * separately as a Spring Data Pageable: page, size, sort=field[,asc|desc].
 */
public record ProductSearchParams(
        @Parameter(description = "Keyword, case-insensitive, in name, SKU or description")
        @Size(max = 100, message = "must be at most {max} characters") String q,

        @Parameter(description = "Category, case-insensitive exact match")
        @Size(max = 60, message = "must be at most {max} characters") String category,

        @Parameter(description = "Minimum price (inclusive)")
        @PositiveOrZero(message = "must be >= 0") BigDecimal minPrice,

        @Parameter(description = "Maximum price (inclusive)")
        @PositiveOrZero(message = "must be >= 0") BigDecimal maxPrice,

        @Parameter(description = "true = only products with stock > 0") Boolean inStock) {

    @AssertTrue(message = "minPrice must be <= maxPrice")
    @Schema(hidden = true)
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }

    public ProductFilter toFilter() {
        return ProductFilter.of(q, category, minPrice, maxPrice, inStock);
    }
}
