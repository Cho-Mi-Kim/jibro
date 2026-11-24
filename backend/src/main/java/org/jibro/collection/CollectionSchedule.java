package org.jibro.collection;

import java.time.*;
import java.util.*;

/**
 * Product schedule in Korea time, independent of host clock timezone.
 */
public final class CollectionSchedule {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final List<Integer> HOURS = List.of(0, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);

    private CollectionSchedule() {
    }

    public static Instant latest(Instant now) {
        ZonedDateTime local = now.atZone(ZONE);
        return HOURS.stream().map(h -> local.toLocalDate().atTime(h, 0).atZone(ZONE).toInstant())
                .filter(slot -> !slot.isAfter(now)).max(Comparator.naturalOrder())
                .orElseGet(() -> local.toLocalDate().minusDays(1).atTime(18, 0).atZone(ZONE).toInstant());
    }

    public static Instant next(Instant now) {
        ZonedDateTime local = now.atZone(ZONE);
        return HOURS.stream().map(h -> local.toLocalDate().atTime(h, 0).atZone(ZONE).toInstant())
                .filter(slot -> slot.isAfter(now)).min(Comparator.naturalOrder())
                .orElseGet(() -> local.toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant());
    }
}
