package com.infy.catalog.product.web.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.math.BigDecimal;
import java.time.Instant;

/** A product in every representation (JSON, XML, HTML, text). The entity is never exposed. */
@JacksonXmlRootElement(localName = "product")
public record ProductResponse(
        long id,
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        int stock,
        Instant createdAt,
        Instant updatedAt) {
}
