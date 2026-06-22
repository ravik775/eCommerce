package org.bgm.apigateway.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderServiceFallback {
    @GetMapping("/fallback/order") public String orderFallback() {
        return "Order service unavailable"; }
}
