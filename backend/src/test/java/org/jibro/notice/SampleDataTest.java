package org.jibro.notice;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SampleDataTest {
    @Test
    void localProfileDoesNotSeedFakeNoticesWithoutExplicitOptIn() {
        try (var context = context(false)) {
            assertTrue(context.getBeansOfType(SampleData.class).isEmpty());
        }
    }

    @Test
    void isolatedFixtureEnvironmentCanStillOptIn() {
        try (var context = context(true)) {
            assertEquals(1, context.getBeansOfType(SampleData.class).size());
        }
    }

    private AnnotationConfigApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("local-sample");
        if (enabled)
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("app.sample-data.enabled", "true")));
        context.registerBean(NoticeService.class, () -> mock(NoticeService.class));
        context.register(SampleData.class);
        context.refresh();
        return context;
    }
}
