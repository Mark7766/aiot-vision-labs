package com.sandy.aiot.vision.collector.controller;

import com.sandy.aiot.vision.collector.repository.DataRecordRepository;
import com.sandy.aiot.vision.collector.service.CollectorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/data")
public class DataController {
    @Autowired
    private DataRecordRepository dataRecordRepository;
    @Autowired
    private CollectorService collectorService;

    @GetMapping
    public String viewData(Model model) {
        model.addAttribute("records", dataRecordRepository.findTop100ByOrderByTimestampDesc());
        return "data";
    }

    @PostMapping("/collect")
    public String collectOnce() {
        collectorService.collectDataOnce();
        return "redirect:/data";
    }
}