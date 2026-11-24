package org.jibro.preparation;

import java.util.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notices")
public class OneTimeCheckController {
    private final OneTimeCheckService service;

    public OneTimeCheckController(OneTimeCheckService service) {
        this.service = service;
    }

    public record Request(@Min(1) int version, @NotNull @Size(max = 100) Map<String, Object> answers) {
    }

    @PostMapping("/{id}/check-once")
    ResponseEntity<OneTimeCheckService.Result> evaluate(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.evaluate(id, request.version(), request.answers()));
    }
}
