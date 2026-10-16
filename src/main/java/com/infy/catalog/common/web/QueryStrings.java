package com.infy.catalog.common.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Builds query strings for HTML links (pagination, sorting) while keeping the current filters. */
public final class QueryStrings {

    private QueryStrings() {
    }

    /** Current parameters with {@code key} replaced by {@code value} (or removed when value is null). */
    public static String with(Map<String, List<String>> current, String key, String value) {
        Map<String, List<String>> params = new LinkedHashMap<>(current);
        params.remove(key);
        if (value != null) {
            params.put(key, List.of(value));
        }
        StringJoiner query = new StringJoiner("&", "?", "");
        query.setEmptyValue("");
        params.forEach((name, values) -> values.stream()
                .filter(v -> v != null && !v.isEmpty())
                .forEach(v -> query.add(encode(name) + "=" + encode(v))));
        return query.toString();
    }

    public static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
