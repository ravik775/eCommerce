package org.bgm.orderservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

   @GetMapping("/")
    String hello(@RequestParam("arg") String arg){

       System.out.println("Arg: "+arg);
       return "Hello";
   }
}
