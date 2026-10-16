package com.infy.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Product Catalog REST API (Spring Boot / Spring MVC).
 * Local run without any setup: {@code mvn spring-boot:run} (in-memory H2 with demo data).
 */
@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
