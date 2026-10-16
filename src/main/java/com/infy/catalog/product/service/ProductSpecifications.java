package com.infy.catalog.product.service;

import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.domain.ProductFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the WHERE clause for a search. Every value is a bind parameter. */
public final class ProductSpecifications {

    private static final char ESCAPE = '\\';

    private ProductSpecifications() {
    }

    public static Specification<Product> matching(ProductFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.keyword() != null) {
                String pattern = "%" + escapeLike(filter.keyword().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("name")), pattern, ESCAPE),
                        cb.like(cb.lower(root.<String>get("sku")), pattern, ESCAPE),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("description"), "")), pattern, ESCAPE)));
            }
            if (filter.category() != null) {
                predicates.add(cb.equal(cb.lower(root.<String>get("category")), filter.category().toLowerCase(Locale.ROOT)));
            }
            if (filter.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("price"), filter.minPrice()));
            }
            if (filter.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("price"), filter.maxPrice()));
            }
            if (filter.inStockOnly()) {
                predicates.add(cb.greaterThan(root.<Integer>get("stock"), 0));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** % and _ in user input match literally. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
