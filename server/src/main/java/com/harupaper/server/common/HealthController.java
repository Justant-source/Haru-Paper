package com.harupaper.server.common;

import com.harupaper.server.common.time.TimeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "time", TimeUtils.toIso8601(java.time.Instant.now())
        );
    }
}
