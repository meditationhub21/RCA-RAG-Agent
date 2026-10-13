package com.example.rca.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Hidden
public class GraphPageController {
    @GetMapping("/graph")
    public String graphPage() { return "forward:/graph.html"; }
}