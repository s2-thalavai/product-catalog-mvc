package com.infy.catalog.product.web.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.infy.catalog.product.domain.ProductPatch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body of PATCH (application/merge-patch+json, JSON or XML): only the fields to change.
 * Omitted = unchanged; "" clears description or category.
 */
@JacksonXmlRootElement(localName = "product")
@Schema(name = "ProductPatchRequest")
public record ProductPatchRequest(
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{2,31}", message = "must be 3-32 characters of A-Z, 0-9 or '-'") String sku,
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        @Size(max = 120, message = "must be at most {max} characters") String name,
        @Size(max = 1000, message = "must be at most {max} characters") String description,
        @Size(max = 60, message = "must be at most {max} characters") String category,
        @DecimalMin(value = "0.00", message = "must be >= 0")
        @DecimalMax(value = "10000000.00", message = "must be <= 10000000")
        @Digits(integer = 8, fraction = 2, message = "must have at most 2 decimal places") BigDecimal price,
        @Min(value = 0, message = "must be >= 0")
        @Max(value = 1_000_000, message = "must be <= 1000000") Integer stock) {

    public ProductPatch toPatch() {
        return new ProductPatch(sku, name, description, category, price, stock);
    }
}
