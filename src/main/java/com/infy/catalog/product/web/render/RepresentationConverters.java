package com.infy.catalog.product.web.render;

import com.infy.catalog.product.web.dto.ProductPage;
import com.infy.catalog.product.web.dto.ProductResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HttpMessageConverters for the two "human" media types. Jackson already handles JSON and XML;
 * these render the same DTOs (and ProblemDetail) as HTML (Thymeleaf templates) and plain text.
 * Because they are converters, every endpoint and every error supports all four formats through
 * normal Spring MVC content negotiation, from a single controller method.
 */
public final class RepresentationConverters {

    private RepresentationConverters() {
    }

    static boolean renderable(Class<?> type) {
        return type == ProductPage.class || type == ProductResponse.class || ProblemDetail.class.isAssignableFrom(type);
    }

    static ProblemView view(ProblemDetail problem) {
        Map<String, Object> props = problem.getProperties() == null ? Map.of() : problem.getProperties();
        Object errors = props.get("errors");
        return new ProblemView(problem.getStatus(), problem.getTitle(), problem.getDetail(),
                String.valueOf(props.getOrDefault("errorCode", "")), String.valueOf(props.getOrDefault("traceId", "")),
                errors instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of());
    }

    /** Write-only base: these media types are never accepted as request bodies. */
    private abstract static class WriteOnly extends AbstractHttpMessageConverter<Object> {

        WriteOnly(MediaType mediaType) {
            super(StandardCharsets.UTF_8, mediaType);
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return renderable(clazz);
        }

        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) {
            throw new HttpMessageNotReadableException("Not readable", inputMessage);
        }

        protected void write(String body, HttpOutputMessage out) throws IOException {
            out.getBody().write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** text/plain */
    public static class Text extends WriteOnly {

        public Text() {
            super(new MediaType("text", "plain", StandardCharsets.UTF_8));
        }

        @Override
        protected void writeInternal(Object body, HttpOutputMessage out) throws IOException {
            String text;
            if (body instanceof ProductPage page) {
                text = TextRenderer.page(page);
            } else if (body instanceof ProductResponse product) {
                text = TextRenderer.product(product);
            } else {
                text = TextRenderer.problem(view((ProblemDetail) body));
            }
            write(text, out);
        }
    }

    /** text/html via Thymeleaf templates in src/main/resources/templates. Output is escaped by Thymeleaf. */
    public static class Html extends WriteOnly {

        static final String LIST_PATH = "/api/v1/products";
        private final ITemplateEngine templates;

        public Html(ITemplateEngine templates) {
            super(new MediaType("text", "html", StandardCharsets.UTF_8));
            this.templates = templates;
        }

        @Override
        protected void writeInternal(Object body, HttpOutputMessage out) throws IOException {
            Context context = new Context(Locale.ENGLISH);
            String template;
            if (body instanceof ProductPage page) {
                template = "products/list";
                context.setVariable("page", page);
                context.setVariable("nav", HtmlNavigation.of(LIST_PATH, currentQuery(), page));
            } else if (body instanceof ProductResponse product) {
                template = "products/detail";
                context.setVariable("product", product);
                context.setVariable("listPath", LIST_PATH);
            } else {
                template = "problem";
                context.setVariable("problem", view((ProblemDetail) body));
            }
            write(templates.process(template, context), out);
        }

        private static Map<String, List<String>> currentQuery() {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return Map.of();
            }
            HttpServletRequest request = attributes.getRequest();
            Map<String, List<String>> query = new LinkedHashMap<>();
            request.getParameterMap().forEach((name, values) -> query.put(name, Arrays.asList(values)));
            return query;
        }
    }
}
