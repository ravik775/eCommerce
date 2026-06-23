package org.bgm.orderservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@Slf4j
public class OrderController {

    @PostMapping()
    public void createOrder(){

    }

    @GetMapping("{id}")
    public void getOrder(@PathVariable("id") long id){

    }

    @PutMapping("{id}/{action}")
    ResponseEntity<Boolean> updateStatus(@PathVariable("id") long orderId, @PathVariable("action") String action){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @GetMapping("customer/{customerId}")
    ResponseEntity<Boolean> getOrderForCustomer(@PathVariable("customerId") String customerId){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

}