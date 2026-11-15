package org.jibro.notice.source;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jibro.notice.HousingModels.Evidence;

/** An original-document reader, deliberately separate from answerable preparation guidance. */
public final class NoticeSourceInformationModels {
    private NoticeSourceInformationModels() {}

    public enum Area { CONDITION, DOCUMENT, SCHEDULE, REFERENCE }
    public static final List<Area> GUIDANCE_AREAS = List.of(Area.CONDITION, Area.DOCUMENT, Area.SCHEDULE);
    public enum State { AVAILABLE, PENDING }

    public record Section(String id, Area area, String title, String text, Evidence evidence) {}

    public record Information(String noticeId, Instant checkedAt, State state, List<Section> sections,
                              List<Area> pendingAreas) {
        public Information {
            sections = List.copyOf(sections);
            pendingAreas = List.copyOf(pendingAreas);
        }

        public static Information pending(UUID noticeId) {
            return new Information(noticeId.toString(), null, State.PENDING, List.of(), GUIDANCE_AREAS);
        }
    }
}
