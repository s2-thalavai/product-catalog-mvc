package com.infy.catalog.product.web.render;

import com.infy.catalog.product.web.dto.ProductPage;
import com.infy.catalog.product.web.dto.ProductResponse;

import java.util.ArrayList;
import java.util.List;

/** text/plain: an aligned table, handy in a terminal ({@code curl -H 'Accept: text/plain'}). */
public final class TextRenderer {

    private static final String[] HEADERS = {"ID", "SKU", "NAME", "CATEGORY", "PRICE", "STOCK"};
    private static final boolean[] RIGHT_ALIGNED = {true, false, false, false, true, true};

    private TextRenderer() {
    }

    public static String page(ProductPage page) {
        List<String[]> rows = new ArrayList<>();
        rows.add(HEADERS);
        for (ProductResponse p : page.items()) {
            rows.add(new String[] {String.valueOf(p.id()), p.sku(), p.name(), nullToEmpty(p.category()),
                    p.price().toPlainString(), String.valueOf(p.stock())});
        }
        int[] widths = new int[HEADERS.length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            out.append(line(rows.get(r), widths)).append('\n');
            if (r == 0) {
                String[] rule = new String[widths.length];
                for (int i = 0; i < widths.length; i++) {
                    rule[i] = "-".repeat(widths[i]);
                }
                out.append(line(rule, widths)).append('\n');
            }
        }
        if (page.items().isEmpty()) {
            out.append("(no products)\n");
        }
        out.append('\n').append(page.totalElements()).append(" product(s)");
        if (page.totalPages() > 0) {
            out.append(", page ").append(page.page() + 1).append(" of ").append(page.totalPages());
        }
        out.append(", page size ").append(page.size());
        if (page.hasNext()) {
            out.append(" - next: page=").append(page.page() + 1);
        }
        return out.append('\n').toString();
    }

    public static String product(ProductResponse p) {
        return "Product " + p.id() + "\n"
                + "  sku:         " + p.sku() + "\n"
                + "  name:        " + p.name() + "\n"
                + "  description: " + nullToEmpty(p.description()) + "\n"
                + "  category:    " + nullToEmpty(p.category()) + "\n"
                + "  price:       " + p.price().toPlainString() + "\n"
                + "  stock:       " + p.stock() + "\n"
                + "  created:     " + p.createdAt() + "\n"
                + "  updated:     " + p.updatedAt() + "\n";
    }

    public static String problem(ProblemView problem) {
        StringBuilder out = new StringBuilder()
                .append(problem.status()).append(' ').append(problem.title())
                .append(" [").append(problem.errorCode()).append("]\n");
        if (problem.detail() != null) {
            out.append(problem.detail()).append('\n');
        }
        problem.errors().forEach(e -> out.append("  - ").append(e).append('\n'));
        return out.append("traceId: ").append(nullToEmpty(problem.traceId())).append('\n').toString();
    }

    private static String line(String[] cells, int[] widths) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append("  ");
            }
            String padding = " ".repeat(widths[i] - cells[i].length());
            out.append(RIGHT_ALIGNED[i] ? padding + cells[i] : cells[i] + padding);
        }
        return out.toString().stripTrailing();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
