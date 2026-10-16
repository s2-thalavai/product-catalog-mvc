package com.infy.catalog.product.web;

import com.infy.catalog.common.web.PaginationLinks;
import com.infy.catalog.config.OpenApiConfig;
import com.infy.catalog.product.domain.InvalidRequestException;
import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.service.ProductService;
import com.infy.catalog.product.web.dto.ProductPage;
import com.infy.catalog.product.web.dto.ProductPatchRequest;
import com.infy.catalog.product.web.dto.ProductRequest;
import com.infy.catalog.product.web.dto.ProductResponse;
import com.infy.catalog.product.web.dto.ProductSearchParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product catalog resource. Thin by design: bind + validate, call the service, map to a DTO,
 * set HTTP headers. Each method produces JSON, XML, HTML and plain text; Spring MVC picks
 * from the Accept header or ?format= (JSON first for clients that accept anything, while
 * browsers, which prefer text/html, get HTML).
 */
@RestController
@RequestMapping(path = "/api/v1/products", produces = {
        MediaType.APPLICATION_JSON_VALUE,
        MediaType.APPLICATION_XML_VALUE,
        MediaType.TEXT_HTML_VALUE,
        MediaType.TEXT_PLAIN_VALUE})
@Tag(name = "Products", description = "Product catalog: CRUD, search and pagination")
public class ProductController {

    static final String MERGE_PATCH_JSON = "application/merge-patch+json";

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search products (paginated)",
            description = "Filters are optional and combined with AND. Paging: page (0-based), size (default 20, "
                    + "capped at 100), sort=field[,asc|desc] with field in id, name, price, stock, createdAt. "
                    + "Links to first/prev/next/last are in the Link header; the total is in X-Total-Count.")
    @ApiResponse(responseCode = "200", description = "A page of products")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (bad filter or sort field)")
    public ResponseEntity<ProductPage> search(@Valid @ParameterObject ProductSearchParams params,
                                              @ParameterObject @PageableDefault(size = 20) Pageable pageable,
                                              HttpServletRequest request) {
        Page<Product> page = service.search(params.toFilter(), withWhitelistedSort(pageable));

        Map<String, List<String>> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> query.put(name, Arrays.asList(values)));
        String baseUrl = ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null).toUriString();

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.getTotalElements()))
                .header(HttpHeaders.LINK, PaginationLinks.header(baseUrl, query, page.getNumber(), page.getSize(), page.getTotalPages()))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(ProductMapper.toPage(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a product",
            description = "Returns an ETag. Send it in If-None-Match to get 304 Not Modified when unchanged.")
    @ApiResponse(responseCode = "200", description = "The product")
    @ApiResponse(responseCode = "304", description = "Not modified")
    @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    public ResponseEntity<ProductResponse> get(@PathVariable long id, WebRequest request) {
        Product product = service.get(id);
        String etag = Etags.of(product.getId(), product.getVersion());
        if (request.checkNotModified("\"" + etag + "\"")) {
            return null;   // 304 already written, with the ETag
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(ProductMapper.toResponse(product));
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @Operation(summary = "Create a product", security = @SecurityRequirement(name = OpenApiConfig.BEARER))
    @ApiResponse(responseCode = "201", description = "Created; Location points to the new product")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST")
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN (scope catalog.write missing)")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_SKU")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest body) {
        Product created = service.create(body.toData());
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location)
                .eTag(Etags.of(created.getId(), created.getVersion()))
                .body(ProductMapper.toResponse(created));
    }

    @PutMapping(path = "/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @Operation(summary = "Replace a product (all writable fields)",
            description = "Optional optimistic locking: send If-Match with the ETag you read; 412 if it changed.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER))
    @ApiResponse(responseCode = "200", description = "The updated product")
    @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_SKU")
    @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED")
    public ResponseEntity<ProductResponse> replace(@PathVariable long id, @Valid @RequestBody ProductRequest body,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Product updated = service.replace(id, body.toData(), Etags.expectedVersion(ifMatch, id));
        return ResponseEntity.ok().eTag(Etags.of(updated.getId(), updated.getVersion())).body(ProductMapper.toResponse(updated));
    }

    @PatchMapping(path = "/{id}", consumes = {MERGE_PATCH_JSON, MediaType.APPLICATION_JSON_VALUE,
            MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @Operation(summary = "Partially update a product (merge patch)",
            description = "Send only the fields to change. \"\" clears description or category. If-Match optional.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER))
    @ApiResponse(responseCode = "200", description = "The updated product")
    @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED")
    public ResponseEntity<ProductResponse> patch(@PathVariable long id, @Valid @RequestBody ProductPatchRequest body,
                                                 @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Product updated = service.patch(id, body.toPatch(), Etags.expectedVersion(ifMatch, id));
        return ResponseEntity.ok().eTag(Etags.of(updated.getId(), updated.getVersion())).body(ProductMapper.toResponse(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a product", security = @SecurityRequirement(name = OpenApiConfig.BEARER))
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED")
    public ResponseEntity<Void> delete(@PathVariable long id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        service.delete(id, Etags.expectedVersion(ifMatch, id));
        return ResponseEntity.noContent().build();
    }

    /**
     * Only whitelisted sort fields; "name" sorts case-insensitively; id is always the final
     * tie-breaker so pages are stable.
     */
    static Pageable withWhitelistedSort(Pageable pageable) {
        List<String> properties = pageable.getSort().stream().map(Sort.Order::getProperty).toList();
        List<String> violations = SortRules.violations(properties);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations);
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            orders.add("name".equals(order.getProperty()) ? order.ignoreCase() : order);
        }
        if (!properties.contains("id")) {
            orders.add(Sort.Order.asc("id"));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
}
