package com.chamberjams.backend;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping({"/", "/healthz", "/api/health"})
    public Map<String, String> health() {
        return Map.of(
            "status", "UP",
            "app", "Chamber Jams backend"
        );
    }
}