package org.jibro.global.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DatabaseHealthRepository database;

    public HealthController(DatabaseHealthRepository database) {
        this.database = database;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<HealthResponse> health() {
        if (!database.isAvailable()) {
            return ResponseEntity.status(503).body(new HealthResponse("DOWN", "DOWN"));
        }
        return ResponseEntity.ok(new HealthResponse("UP", "UP"));
    }

    public record HealthResponse(String status, String database) {
    }
}
