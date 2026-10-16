package com.infy.catalog.product.web.render;

import com.infy.catalog.common.web.QueryStrings;
import com.infy.catalog.product.web.dto.ProductPage;

import java.util.List;
import java.util.Map;

/**
 * Everything the HTML list template needs besides the data: current filter values for the
 * search form and ready-made links (pager, sort, other formats) that keep the current filters.
 * All values are plain strings; Thymeleaf escapes them on output.
 */
public record HtmlNavigation(
        String listPath,
        String q, String category, String minPrice, String maxPrice, boolean inStock, String sort, String size,
        String firstUrl, String prevUrl, String nextUrl, String lastUrl,
        String jsonUrl, String xmlUrl, String textUrl) {

    public static final List<String[]> SORT_OPTIONS = List.of(
            new String[] {"", "Id"},
            new String[] {"name", "Name (A-Z)"},
            new String[] {"name,desc", "Name (Z-A)"},
            new String[] {"price", "Price (low-high)"},
            new String[] {"price,desc", "Price (high-low)"},
            new String[] {"stock,desc", "Stock (high-low)"},
            new String[] {"createdAt,desc", "Newest"});

    public static HtmlNavigation of(String listPath, Map<String, List<String>> query, ProductPage page) {
        int last = Math.max(page.totalPages() - 1, 0);
        Map<String, List<String>> withoutFormat = new java.util.LinkedHashMap<>(query);
        withoutFormat.remove("format");
        return new HtmlNavigation(
                listPath,
                QueryStrings.first(query, "q"),
                QueryStrings.first(query, "category"),
                QueryStrings.first(query, "minPrice"),
                QueryStrings.first(query, "maxPrice"),
                "true".equals(QueryStrings.first(query, "inStock")),
                QueryStrings.first(query, "sort"),
                QueryStrings.first(query, "size"),
                page.hasPrevious() ? pageUrl(listPath, withoutFormat, 0) : null,
                page.hasPrevious() ? pageUrl(listPath, withoutFormat, Math.min(page.page() - 1, last)) : null,
                page.hasNext() ? pageUrl(listPath, withoutFormat, page.page() + 1) : null,
                page.hasNext() ? pageUrl(listPath, withoutFormat, last) : null,
                listPath + QueryStrings.with(withoutFormat, "format", "json"),
                listPath + QueryStrings.with(withoutFormat, "format", "xml"),
                listPath + QueryStrings.with(withoutFormat, "format", "text"));
    }

    private static String pageUrl(String listPath, Map<String, List<String>> query, int page) {
        return listPath + QueryStrings.with(query, "page", String.valueOf(page));
    }

    public List<String[]> sortOptions() {
        return SORT_OPTIONS;
    }
}
