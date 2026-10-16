package com.infy.catalog.product.service;

import com.infy.catalog.product.domain.DuplicateSkuException;
import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.domain.ProductData;
import com.infy.catalog.product.domain.ProductNotFoundException;
import com.infy.catalog.product.domain.ProductPatch;
import com.infy.catalog.product.domain.VersionMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Business rules with a mocked repository (checklist: service rules via plain unit tests). */
class ProductServiceTest {

    private ProductRepository repository;
    private ProductService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        service = new ProductService(repository);
        when(repository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createSavesNormalizedData() {
        Product created = service.create(ProductData.normalized(" fit-01 ", " Mat ", " ", "Fitness", new BigDecimal("999.5"), 3));

        assertThat(created.getSku()).isEqualTo("FIT-01");
        assertThat(created.getName()).isEqualTo("Mat");
        assertThat(created.getDescription()).isNull();
        assertThat(created.getPrice()).isEqualByComparingTo("999.50");
        assertThat(created.getPrice().scale()).isEqualTo(2);
    }

    @Test
    void createRejectsDuplicateSkuFromCheckAndFromConstraint() {
        when(repository.existsBySku("DUP-1")).thenReturn(true);
        assertThatThrownBy(() -> service.create(data("DUP-1"))).isInstanceOf(DuplicateSkuException.class);

        when(repository.saveAndFlush(any(Product.class))).thenThrow(new DataIntegrityViolationException("uk_product_sku"));
        assertThatThrownBy(() -> service.create(data("RACE-1"))).isInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9)).isInstanceOf(ProductNotFoundException.class).hasMessageContaining("9");
    }

    @Test
    void replaceChecksExpectedVersionBeforeWriting() {
        Product existing = new Product(data("SKU-1"));
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.replace(1, data("SKU-1"), OptionalLong.of(7)))
                .isInstanceOf(VersionMismatchException.class);
        verify(repository, never()).saveAndFlush(any());

        Product replaced = service.replace(1, ProductData.normalized("SKU-1", "New", null, null, BigDecimal.ONE, 1), OptionalLong.of(0));
        assertThat(replaced.getName()).isEqualTo("New");
    }

    @Test
    void replaceRejectsSkuOwnedByAnotherProduct() {
        when(repository.findById(1L)).thenReturn(Optional.of(new Product(data("SKU-1"))));
        when(repository.existsBySkuAndIdNot("SKU-2", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.replace(1, data("SKU-2"), OptionalLong.empty()))
                .isInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void patchChangesOnlyGivenFieldsAndEmptyStringClears() {
        Product existing = new Product(ProductData.normalized("SKU-1", "Lamp", "Warm", "Home", new BigDecimal("10.00"), 5));
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        Product patched = service.patch(1, new ProductPatch(null, null, null, "", null, 0), OptionalLong.empty());

        assertThat(patched.getName()).isEqualTo("Lamp");
        assertThat(patched.getDescription()).isEqualTo("Warm");
        assertThat(patched.getCategory()).isNull();
        assertThat(patched.getStock()).isZero();
    }

    @Test
    void deleteHonoursExpectedVersion() {
        Product existing = new Product(data("SKU-1"));
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(1, OptionalLong.of(3))).isInstanceOf(VersionMismatchException.class);
        service.delete(1, OptionalLong.empty());
        verify(repository).delete(existing);
    }

    private static ProductData data(String sku) {
        return ProductData.normalized(sku, "Name", null, "Cat", new BigDecimal("10.00"), 1);
    }
}
