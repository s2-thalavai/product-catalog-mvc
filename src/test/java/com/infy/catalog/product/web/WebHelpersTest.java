package com.infy.catalog.product.web;

import com.infy.catalog.common.error.ErrorCode;
import com.infy.catalog.common.web.PaginationLinks;
import com.infy.catalog.common.web.QueryStrings;
import com.infy.catalog.config.CatalogProperties;
import com.infy.catalog.product.domain.VersionMismatchException;
import com.infy.catalog.product.web.dto.ProductPage;
import com.infy.catalog.product.web.dto.ProductResponse;
import com.infy.catalog.product.web.render.HtmlNavigation;
import com.infy.catalog.product.web.render.ProblemView;
import com.infy.catalog.product.web.render.TextRenderer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Framework-free helpers: paging links, ETags, sort whitelist, text rendering, HTML navigation, config rules. */
class WebHelpersTest {

    private static final Instant T = Instant.parse("2026-10-16T03:00:00Z");
    private static final ProductResponse MOUSE =
            new ProductResponse(1, "ELEC-MOUSE-01", "Wireless Mouse", "2.4 GHz", "Electronics", new BigDecimal("799.00"), 120, T, T);

    @Test
    void paginationRelations() {
        assertEquals(List.of("first", "next", "last"), List.copyOf(PaginationLinks.relations(0, 3).keySet()));
        assertEquals(Map.of("first", 0, "prev", 1, "next", 3, "last", 4), PaginationLinks.relations(2, 5));
        assertEquals(Map.of("first", 0, "prev", 2, "last", 2), PaginationLinks.relations(9, 3));
        assertEquals(Map.of("first", 0, "last", 0), PaginationLinks.relations(0, 0));
    }

    @Test
    void linkHeaderKeepsFiltersAndUsesEffectiveSize() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("q", List.of("lamp"));
        query.put("page", List.of("0"));
        query.put("size", List.of("500"));

        String header = PaginationLinks.header("http://h/api/v1/products", query, 0, 100, 2);

        assertEquals("<http://h/api/v1/products?q=lamp&size=100&page=0>; rel=\"first\", "
                + "<http://h/api/v1/products?q=lamp&size=100&page=1>; rel=\"next\", "
                + "<http://h/api/v1/products?q=lamp&size=100&page=1>; rel=\"last\"", header);
    }

    @Test
    void etagsCarryIdAndVersion() {
        assertEquals("7-3", Etags.of(7, 3));
        assertEquals(OptionalLong.empty(), Etags.expectedVersion(null, 7));
        assertEquals(OptionalLong.empty(), Etags.expectedVersion("*", 7));
        assertEquals(OptionalLong.of(3), Etags.expectedVersion("\"7-3\"", 7));
        assertThrows(VersionMismatchException.class, () -> Etags.expectedVersion("\"8-3\"", 7));
        assertThrows(VersionMismatchException.class, () -> Etags.expectedVersion("\"stale\"", 7));
        assertThrows(VersionMismatchException.class, () -> Etags.expectedVersion("W/\"7-3\"", 7));
    }

    @Test
    void sortWhitelist() {
        assertTrue(SortRules.violations(List.of("price", "name", "createdAt")).isEmpty());
        assertEquals(1, SortRules.violations(List.of("price", "password", "colour")).size());
    }

    @Test
    void textTableIsAligned() {
        ProductResponse hub = new ProductResponse(12, "ELEC-HUB-01", "USB-C Hub", null, "Electronics", new BigDecimal("1899.00"), 6, T, T);
        String[] lines = TextRenderer.page(new ProductPage(List.of(MOUSE, hub), 0, 2, 5, 3, true, false)).split("\n");

        assertTrue(lines[0].startsWith("ID  SKU"));
        assertEquals(lines[2].indexOf("799.00") + 6, lines[3].indexOf("1899.00") + 7, "prices right-aligned");
        assertTrue(String.join("\n", lines).contains("5 product(s), page 1 of 3, page size 2 - next: page=1"));
        assertTrue(TextRenderer.product(MOUSE).contains("  price:       799.00"));
    }

    @Test
    void textProblem() {
        String text = TextRenderer.problem(new ProblemView(404, "Product not found", "Product 9 was not found",
                "PRODUCT_NOT_FOUND", "t-1", List.of()));
        assertTrue(text.startsWith("404 Product not found [PRODUCT_NOT_FOUND]"));
        assertTrue(text.endsWith("traceId: t-1\n"));
    }

    @Test
    void htmlNavigationKeepsFiltersAndDropsFormat() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("q", List.of("mouse"));
        query.put("sort", List.of("price,desc"));
        query.put("format", List.of("html"));
        query.put("page", List.of("1"));

        HtmlNavigation nav = HtmlNavigation.of("/api/v1/products", query, new ProductPage(List.of(MOUSE), 1, 1, 3, 3, true, true));

        assertEquals("mouse", nav.q());
        assertEquals("price,desc", nav.sort());
        assertEquals("/api/v1/products?q=mouse&sort=price%2Cdesc&page=0", nav.firstUrl());
        assertEquals("/api/v1/products?q=mouse&sort=price%2Cdesc&page=2", nav.nextUrl());
        assertEquals("/api/v1/products?q=mouse&sort=price%2Cdesc&page=1&format=json", nav.jsonUrl());
        assertFalse(nav.inStock());

        HtmlNavigation single = HtmlNavigation.of("/api/v1/products", Map.of(), new ProductPage(List.of(), 0, 20, 0, 0, false, false));
        assertNull(single.prevUrl());
        assertNull(single.nextUrl());
    }

    @Test
    void queryStringsEncodeAndReplace() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("q", List.of("50% off"));
        query.put("page", List.of("3"));
        assertEquals("?q=50%25+off&page=4", QueryStrings.with(query, "page", "4"));
        assertEquals("", QueryStrings.with(Map.of(), "page", null));
    }

    @Test
    void errorCodesForFrameworkStatuses() {
        assertEquals(ErrorCode.UNAUTHORIZED, ErrorCode.forStatus(401));
        assertEquals(ErrorCode.NOT_ACCEPTABLE, ErrorCode.forStatus(406));
        assertEquals(ErrorCode.INTERNAL_ERROR, ErrorCode.forStatus(503));
        assertEquals("https://catalog.infy.com/problems/duplicate-sku", ErrorCode.DUPLICATE_SKU.type().toString());
        assertEquals("about:blank", ErrorCode.NOT_FOUND.type().toString());
    }

    @Test
    void corsOriginsMustBeExplicitAndSecretsLongEnough() {
        assertEquals(List.of("https://shop.example.com"),
                new CatalogProperties.Cors(List.of(" https://shop.example.com/ ", "")).allowedOrigins());
        assertThrows(IllegalArgumentException.class, () -> new CatalogProperties.Cors(List.of("*")));
        assertThrows(IllegalArgumentException.class, () -> new CatalogProperties.Cors(List.of("https://*.example.com")));
        assertThrows(IllegalArgumentException.class, () -> new CatalogProperties.Jwt("too-short"));
        assertNull(new CatalogProperties.Jwt("  ").hmacSecret());
    }
}
