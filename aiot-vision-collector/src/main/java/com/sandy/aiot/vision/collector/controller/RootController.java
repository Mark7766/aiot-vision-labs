package com.sandy.aiot.vision.collector.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @GetMapping("/")
    public String root() {
        // Minimal redirect to unified runtime page
        return "redirect:/data";
    }
}

