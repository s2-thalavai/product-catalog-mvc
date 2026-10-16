package com.infy.catalog.product.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sort whitelist. Spring Data would otherwise try to sort by any entity property a client names
 * (and leak entity structure in the error). Only these are public API.
 */
public final class SortRules {

    public static final Set<String> ALLOWED = Set.of("id", "name", "price", "stock", "createdAt");

    private SortRules() {
    }

    /** One message per unknown property; empty when all are allowed. */
    public static List<String> violations(List<String> properties) {
        List<String> errors = new ArrayList<>();
        for (String property : properties) {
            if (!ALLOWED.contains(property)) {
                errors.add("sort field must be one of: id, name, price, stock, createdAt");
                break;
            }
        }
        return errors;
    }
}
