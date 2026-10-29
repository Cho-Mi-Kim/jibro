package org.jibro.collection;

import java.util.List;

import org.jibro.notice.HousingModels.Collection;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/collections")
public class CollectionController {
    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @GetMapping
    List<Collection> list() {
        return service.list();
    }

    @PostMapping
    Collection collect(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "1") int startPage) {
        return service.collect(jwt.getSubject(), startPage);
    }
}

