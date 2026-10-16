package com.infy.catalog.product.web.render;

import java.util.List;

/** Framework-free view of a problem, for the HTML and text renderers. */
public record ProblemView(int status, String title, String detail, String errorCode, String traceId, List<String> errors) {

    public ProblemView {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
