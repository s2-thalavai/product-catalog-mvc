package com.infy.catalog.product.web;

import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.web.dto.ProductPage;
import com.infy.catalog.product.web.dto.ProductResponse;
import org.springframework.data.domain.Page;

/** Entity -> DTO. Kept separate so the JPA entity never reaches a client. */
final class ProductMapper {

    private ProductMapper() {
    }

    static ProductResponse toResponse(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getCategory(),
                p.getPrice(), p.getStock(), p.getCreatedAt(), p.getUpdatedAt());
    }

    static ProductPage toPage(Page<Product> page) {
        return new ProductPage(page.getContent().stream().map(ProductMapper::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.hasNext(), page.hasPrevious());
    }
}
