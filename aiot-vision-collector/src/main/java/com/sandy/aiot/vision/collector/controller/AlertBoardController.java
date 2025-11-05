package com.sandy.aiot.vision.collector.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * View controller for the alert monitoring big screen.
 * Keeps logic minimal; data loaded via AJAX from REST endpoints.
 */
@Controller
public class AlertBoardController {

    @GetMapping("/alerts/board")
    public String board(Model model) {
        // Future: server-side preloading, for now front-end fetches.
        return "alerts-board";
    }
}

