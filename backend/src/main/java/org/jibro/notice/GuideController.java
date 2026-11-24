package org.jibro.notice;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GuideController {
    private final GuideService guides;

    public GuideController(GuideService guides) {
        this.guides = guides;
    }

    @GetMapping("/meta")
    Map<String, Object> meta() {
        return guides.meta();
    }

    @GetMapping("/guides")
    GuideService.GuidePage guides(@RequestParam(defaultValue = "") String category,
                                  @RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(defaultValue = "0") int page) {
        return guides.guides(category, keyword, page);
    }
}
