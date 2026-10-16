package com.infy.catalog.product.service;

import com.infy.catalog.product.domain.DuplicateSkuException;
import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.domain.ProductData;
import com.infy.catalog.product.domain.ProductFilter;
import com.infy.catalog.product.domain.ProductNotFoundException;
import com.infy.catalog.product.domain.ProductPatch;
import com.infy.catalog.product.domain.VersionMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.OptionalLong;

/**
 * Business rules. Knows nothing about HTTP, JSON or XML.
 * Writes take an optional expected version (from If-Match): checked here, and enforced
 * atomically by JPA {@code @Version} when the transaction flushes.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Page<Product> search(ProductFilter filter, Pageable pageable) {
        return repository.findAll(ProductSpecifications.matching(filter), pageable);
    }

    public Product get(long id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional
    public Product create(ProductData data) {
        if (repository.existsBySku(data.sku())) {
            throw new DuplicateSkuException(data.sku());
        }
        return saveAndFlush(new Product(data), data.sku());
    }

    /** Full replace (PUT). 404 if missing: PUT never creates. */
    @Transactional
    public Product replace(long id, ProductData data, OptionalLong expectedVersion) {
        Product product = getForUpdate(id, expectedVersion);
        if (repository.existsBySkuAndIdNot(data.sku(), id)) {
            throw new DuplicateSkuException(data.sku());
        }
        product.apply(data);
        return saveAndFlush(product, data.sku());
    }

    @Transactional
    public Product patch(long id, ProductPatch patch, OptionalLong expectedVersion) {
        Product product = getForUpdate(id, expectedVersion);
        return replace(id, patch.applyTo(product), OptionalLong.empty());
    }

    @Transactional
    public void delete(long id, OptionalLong expectedVersion) {
        repository.delete(getForUpdate(id, expectedVersion));
    }

    private Product getForUpdate(long id, OptionalLong expectedVersion) {
        Product product = get(id);
        if (expectedVersion.isPresent() && expectedVersion.getAsLong() != product.getVersion()) {
            throw new VersionMismatchException(id);
        }
        return product;
    }

    /** Flush now so constraint and version conflicts surface here, as domain exceptions. */
    private Product saveAndFlush(Product product, String sku) {
        try {
            return repository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSkuException(sku);   // unique index won a race with the existsBySku check
        }
    }
}
