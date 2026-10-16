package com.infy.catalog.product.web.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

/**
 * One page of products. A stable, documented shape: Spring Data's Page/PageImpl is
 * deliberately not serialized directly (its JSON is an implementation detail).
 * JSON: {"items":[...],"page":0,"size":20,"totalElements":25,"totalPages":2,"hasNext":true,"hasPrevious":false}
 * XML:  {@code <productPage><items><product>...</product></items><page>0</page>...</productPage>}
 */
@JacksonXmlRootElement(localName = "productPage")
public record ProductPage(
        @JacksonXmlElementWrapper(localName = "items")
        @JacksonXmlProperty(localName = "product")
        List<ProductResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {
}
