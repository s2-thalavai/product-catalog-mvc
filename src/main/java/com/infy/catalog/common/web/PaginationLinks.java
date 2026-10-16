package com.infy.catalog.common.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** RFC 8288 Link header for a page of results (first, prev, next, last), keeping all current filters. */
public final class PaginationLinks {

    private PaginationLinks() {
    }

    /**
     * Relation -> page index, in the order first, prev, next, last. "prev" is omitted on the first
     * page and "next" on the last; a page past the end links back to the last real page.
     */
    public static Map<String, Integer> relations(int page, int totalPages) {
        Map<String, Integer> links = new LinkedHashMap<>();
        int last = Math.max(totalPages - 1, 0);
        links.put("first", 0);
        if (page > 0) {
            links.put("prev", Math.min(page - 1, last));
        }
        if (page < last) {
            links.put("next", page + 1);
        }
        links.put("last", last);
        return links;
    }

    /** e.g. {@code <http://host/api/v1/products?q=lamp&page=1&size=20>; rel="next", ...} */
    public static String header(String baseUrl, Map<String, List<String>> query, int page, int size, int totalPages) {
        StringJoiner header = new StringJoiner(", ");
        relations(page, totalPages).forEach((rel, target) -> {
            Map<String, List<String>> params = new LinkedHashMap<>(query);
            params.remove("page");
            params.remove("size");
            params.put("size", List.of(String.valueOf(size)));
            header.add("<" + baseUrl + QueryStrings.with(params, "page", String.valueOf(target)) + ">; rel=\"" + rel + "\"");
        });
        return header.toString();
    }
}
