package com.infy.catalog.product.web.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.infy.catalog.product.domain.ProductData;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body of POST and PUT (all writable fields). JSON or XML ({@code <product>...</product>}).
 * Every string is bounded; limits match the column sizes in V1__create_product_table.sql.
 */
@JacksonXmlRootElement(localName = "product")
@Schema(name = "ProductRequest")
public record ProductRequest(
        @NotBlank(message = "is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{2,31}", message = "must be 3-32 characters of A-Z, 0-9 or '-'")
        @Schema(example = "FIT-KBEL-01") String sku,

        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most {max} characters")
        @Schema(example = "Kettlebell 8 kg") String name,

        @Size(max = 1000, message = "must be at most {max} characters") String description,

        @Size(max = 60, message = "must be at most {max} characters")
        @Schema(example = "Fitness") String category,

        @NotNull(message = "is required")
        @DecimalMin(value = "0.00", message = "must be >= 0")
        @DecimalMax(value = "10000000.00", message = "must be <= 10000000")
        @Digits(integer = 8, fraction = 2, message = "must have at most 2 decimal places")
        @Schema(example = "1899.00") BigDecimal price,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must be >= 0")
        @Max(value = 1_000_000, message = "must be <= 1000000")
        @Schema(example = "25") Integer stock) {

    public ProductData toData() {
        return ProductData.normalized(sku, name, description, category, price, stock);
    }
}
