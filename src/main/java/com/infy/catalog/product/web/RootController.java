package com.infy.catalog.product.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** GET / -> the catalog (handy in a browser). */
@Controller
@Hidden
public class RootController {

    @GetMapping("/")
    public String root() {
        return "redirect:/api/v1/products";
    }
}
