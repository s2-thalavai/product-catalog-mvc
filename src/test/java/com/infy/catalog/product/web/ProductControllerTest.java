package com.infy.catalog.product.web;

import com.infy.catalog.common.error.ProblemAuthenticationHandlers;
import com.infy.catalog.config.SecurityConfig;
import com.infy.catalog.config.WebConfig;
import com.infy.catalog.product.domain.DuplicateSkuException;
import com.infy.catalog.product.domain.Product;
import com.infy.catalog.product.domain.ProductData;
import com.infy.catalog.product.domain.ProductFilter;
import com.infy.catalog.product.domain.ProductNotFoundException;
import com.infy.catalog.product.domain.VersionMismatchException;
import com.infy.catalog.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalLong;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

/**
 * Web slice (checklist: @WebMvcTest covers status codes, headers, JSON body, validation and
 * error paths). The service is mocked; security and converters are the real ones.
 */
@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, WebConfig.class, ProblemAuthenticationHandlers.class})
class ProductControllerTest {

    private static final String API = "/api/v1/products";
    private static final String VALID = """
            {"sku":"fit-01","name":"Yoga Mat","category":"Fitness","price":999.5,"stock":10}""";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProductService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ reads (public)

    @Test
    void searchReturnsPageWithPagingHeaders() throws Exception {
        when(service.search(any(ProductFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(1, 0)), PageRequest.of(0, 1), 3));

        mvc.perform(get(API).param("size", "1").param("q", "mat"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andExpect(header().string("Link", containsString("q=mat")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("FIT-01"))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void sameDataAsXmlHtmlAndText() throws Exception {
        when(service.get(1)).thenReturn(product(1, 0));

        mvc.perform(get(API + "/1").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(xpath("/product/sku").string("FIT-01"));
        mvc.perform(get(API + "/1").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<h2>Yoga Mat</h2>")))
                .andExpect(header().string("Content-Security-Policy", startsWith("default-src 'none'")));
        mvc.perform(get(API + "/1").param("format", "text"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("name:        Yoga Mat")));
    }

    @Test
    void getSendsEtagAndHonoursIfNoneMatch() throws Exception {
        when(service.get(1)).thenReturn(product(1, 4));

        mvc.perform(get(API + "/1")).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1-4\""));
        mvc.perform(get(API + "/1").header("If-None-Match", "\"1-4\"")).andExpect(status().isNotModified());
    }

    @Test
    void unknownProductIsProblemJson() throws Exception {
        when(service.get(99)).thenThrow(new ProductNotFoundException(99));

        mvc.perform(get(API + "/99").header("X-Correlation-Id", "trace-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.instance").value(API + "/99"))
                .andExpect(header().string("X-Correlation-Id", "trace-1"));
    }

    @Test
    void invalidSearchParametersAndSortAre400() throws Exception {
        mvc.perform(get(API).param("minPrice", "cheap"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasItem("minPrice has an invalid value")));
        mvc.perform(get(API).param("minPrice", "500").param("maxPrice", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("minPrice must be <= maxPrice")));
        mvc.perform(get(API).param("sort", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]", containsString("sort field must be one of")));
    }

    @Test
    void notAcceptableIs406Problem() throws Exception {
        mvc.perform(get(API).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable());
    }

    // ------------------------------------------------------------------ writes (need scope catalog.write)

    @Test
    void writesNeedAToken() throws Exception {
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        verify(service, never()).create(any());
    }

    @Test
    void writesNeedTheWriteScope() throws Exception {
        mvc.perform(post(API).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void createReturns201WithLocationAndEtag() throws Exception {
        when(service.create(any(ProductData.class))).thenReturn(product(26, 0));

        mvc.perform(writer(post(API)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/products/26"))
                .andExpect(header().string("ETag", "\"26-0\""))
                .andExpect(jsonPath("$.id").value(26));
    }

    @Test
    void createAcceptsXml() throws Exception {
        when(service.create(any(ProductData.class))).thenReturn(product(27, 0));

        mvc.perform(writer(post(API)).contentType(MediaType.APPLICATION_XML)
                        .content("<product><sku>FIT-02</sku><name>Ball</name><price>10.00</price><stock>1</stock></product>"))
                .andExpect(status().isCreated());
    }

    @Test
    void validationReportsEveryField() throws Exception {
        mvc.perform(writer(post(API)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"x\",\"name\":\"\",\"price\":-1,\"stock\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors", hasItem("name is required")))
                .andExpect(jsonPath("$.errors", hasItem("price must be >= 0")));
    }

    @Test
    void malformedBodyIs400WithoutInternals() throws Exception {
        mvc.perform(writer(post(API)).contentType(MediaType.APPLICATION_JSON).content("{\"sku\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("com.fasterxml"))));
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        mvc.perform(writer(post(API)).contentType(MediaType.TEXT_PLAIN).content("sku,name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void duplicateSkuIs409() throws Exception {
        when(service.create(any(ProductData.class))).thenThrow(new DuplicateSkuException("FIT-01"));

        mvc.perform(writer(post(API)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_SKU"));
    }

    @Test
    void patchWithStaleEtagIs412() throws Exception {
        when(service.patch(eq(1L), any(), eq(OptionalLong.of(1)))).thenThrow(new VersionMismatchException(1));

        mvc.perform(writer(patch(API + "/1")).contentType("application/merge-patch+json")
                        .header("If-Match", "\"1-1\"").content("{\"stock\":0}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("PRECONDITION_FAILED"));
    }

    @Test
    void deleteIs204() throws Exception {
        mvc.perform(writer(delete(API + "/1"))).andExpect(status().isNoContent());
        verify(service).delete(anyLong(), eq(OptionalLong.empty()));
    }

    private static MockHttpServletRequestBuilder writer(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.WRITE_AUTHORITY)));
    }

    private static Product product(long id, long version) {
        Product product = new Product(ProductData.normalized("FIT-01", "Yoga Mat", "6 mm", "Fitness", new BigDecimal("999.50"), 10));
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "version", version);
        return product;
    }
}
