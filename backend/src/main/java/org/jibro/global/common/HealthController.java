package org.jibro.global.common;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<HealthResponse> health() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (!Integer.valueOf(1).equals(result)) {
                return ResponseEntity.status(503).body(new HealthResponse("DOWN", "DOWN"));
            }
            return ResponseEntity.ok(new HealthResponse("UP", "UP"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(503).body(new HealthResponse("DOWN", "DOWN"));
        }
    }

    public record HealthResponse(String status, String database) {
    }
}
