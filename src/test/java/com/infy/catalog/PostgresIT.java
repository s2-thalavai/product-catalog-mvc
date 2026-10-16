package com.infy.catalog;

import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.domain.ProductData;
import com.infy.catalog.product.domain.ProductFilter;
import com.infy.catalog.product.domain.VersionMismatchException;
import com.infy.catalog.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same schema, queries and locking on a real PostgreSQL 16 (checklist: a few Testcontainers ITs).
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "catalog.seed-data=true")
@ActiveProfiles("postgres")
class PostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private ProductService service;

    @Test
    void migrationsAndSeedDataLoad() {
        Page<Product> books = service.search(ProductFilter.of(null, "BOOKS", null, null, null), PageRequest.of(0, 10));
        assertThat(books.getTotalElements()).isEqualTo(3);
    }

    @Test
    void keywordSearchEscapesLikeWildcardsAndSortsCaseInsensitively() {
        service.create(ProductData.normalized("PG-PCT-1", "50% Off Mug", null, "pg-it", BigDecimal.ONE, 1));
        service.create(ProductData.normalized("PG-PCT-2", "500 Mug", null, "pg-it", BigDecimal.ONE, 1));

        Page<Product> hits = service.search(ProductFilter.of("50%", "pg-it", null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Order.asc("name").ignoreCase())));

        assertThat(hits.getContent()).extracting(Product::getSku).containsExactly("PG-PCT-1");
    }

    @Test
    void optimisticLockingUsesTheVersionColumn() {
        Product created = service.create(ProductData.normalized("PG-LOCK-1", "Lock", null, "pg-it", BigDecimal.TEN, 1));
        Product updated = service.replace(created.getId(),
                ProductData.normalized("PG-LOCK-1", "Lock v2", null, "pg-it", BigDecimal.TEN, 2), OptionalLong.of(created.getVersion()));

        assertThat(updated.getVersion()).isEqualTo(created.getVersion() + 1);
        assertThatThrownBy(() -> service.replace(created.getId(),
                ProductData.normalized("PG-LOCK-1", "Lock v3", null, "pg-it", BigDecimal.TEN, 3), OptionalLong.of(created.getVersion())))
                .isInstanceOf(VersionMismatchException.class);
    }
}
