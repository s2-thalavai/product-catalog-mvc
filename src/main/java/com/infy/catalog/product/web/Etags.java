package com.infy.catalog.product.web;

import com.infy.catalog.product.domain.VersionMismatchException;

import java.util.OptionalLong;

/**
 * Entity tags are {@code "<id>-<version>"} (JPA @Version), so they change with every update.
 * Used for conditional GET (If-None-Match -> 304) and optimistic locking (If-Match -> 412).
 */
public final class Etags {

    private Etags() {
    }

    /** Opaque value without quotes. */
    public static String of(long id, long version) {
        return id + "-" + version;
    }

    /**
     * Expected version from an If-Match header for product {@code id}.
     * Absent header or "*" = no expectation. A tag for another product, a weak tag or garbage
     * can never match, so it fails with 412 like a stale tag.
     */
    public static OptionalLong expectedVersion(String ifMatch, long id) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return OptionalLong.empty();
        }
        String tag = ifMatch.trim();
        if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
            tag = tag.substring(1, tag.length() - 1);
        }
        String prefix = id + "-";
        if (tag.startsWith(prefix)) {
            try {
                return OptionalLong.of(Long.parseLong(tag.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // falls through to mismatch
            }
        }
        throw new VersionMismatchException(id);
    }
}
