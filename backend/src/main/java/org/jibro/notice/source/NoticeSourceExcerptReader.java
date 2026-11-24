package org.jibro.notice.source;

import static org.jibro.notice.source.NoticeSourceInformationModels.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.EvidenceValidation;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.source.NoticeSourceInformationRepository.Snapshot;

/**
 * Classifiers choose tabs only; displayed text always remains the entire original extracted part.
 */
public final class NoticeSourceExcerptReader {
    static final int MAX_PART_CHARACTERS = 6000;
    static final int MAX_SECTIONS_PER_AREA = 3;
    private static final int MAX_SCAN_CHARACTERS = 250_000;
    private static final int MAX_SOURCES = 100;
    private static final int MAX_PARTS_PER_SOURCE = 200;
    private static final Pattern CONDITION = Pattern.compile("무주택|소득|자산|연령|만\\s*\\d+\\s*세|신청\\s*자격|입주\\s*자격|자격\\s*기준|가구원|거주지|순위");
    private static final Pattern DOCUMENT = Pattern.compile("제출\\s*서류|증명서|주민등록|동의서|확약서|발급|구비\\s*서류");
    private static final Pattern SCHEDULE = Pattern.compile("접수|마감|발표|신청\\s*기간|공급\\s*일정");
    private static final String CONDITION_TERMS = "신청\\s*자격|입주\\s*자격|자격\\s*기준";
    private static final String DOCUMENT_TERMS = "제출\\s*서류|구비\\s*서류";
    private static final String SCHEDULE_TERMS = "공급\\s*일정|신청\\s*일정|접수\\s*일정|신청\\s*기간|접수\\s*기간";
    private static final Pattern CONDITION_HEADING = heading(CONDITION_TERMS);
    private static final Pattern DOCUMENT_HEADING = heading(DOCUMENT_TERMS);
    private static final Pattern SCHEDULE_HEADING = heading(SCHEDULE_TERMS);
    private static final Pattern CONDITION_NUMBERED = numberedHeading(CONDITION_TERMS);
    private static final Pattern DOCUMENT_NUMBERED = numberedHeading(DOCUMENT_TERMS);
    private static final Pattern SCHEDULE_NUMBERED = numberedHeading(SCHEDULE_TERMS);

    private static final Pattern DRAWING = Pattern.compile("평면도|배치도|위치도");

    private record OriginalPart(Part part, Evidence evidence, int order, boolean drawing) {
    }

    private NoticeSourceExcerptReader() {
    }

    public static Information read(Snapshot snapshot, Set<String> allowedHosts) {
        var sections = new ArrayList<Section>();
        var seen = new LinkedHashSet<String>();
        var candidates = new ArrayList<OriginalPart>();
        int scanned = 0;
        String checked = snapshot.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        for (Source source : snapshot.sources().stream().limit(MAX_SOURCES).toList()) {
            if (!trusted(source, allowedHosts)) continue;
            for (Part part : source.context().stream().limit(MAX_PARTS_PER_SOURCE).toList()) {
                if (!completePart(part)) continue;
                if (scanned + part.text().length() > MAX_SCAN_CHARACTERS) break;
                scanned += part.text().length();
                String title = source.label() + " · " + part.location();
                var evidence = new Evidence(title, source.url(), checked);
                try {
                    EvidenceValidation.validate(evidence);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                candidates.add(new OriginalPart(part, evidence, candidates.size(), DRAWING.matcher(source.label()).find()));
            }
        }
        for (Area area : Area.values()) {
            int count = 0;
            // Explicit section headings precede passing mentions on cover pages. Keep the complete part.
            var selected = candidates.stream().filter(candidate -> area == Area.REFERENCE ? candidate.drawing()
                            : !candidate.drawing() && category(area).matcher(candidate.part().text()).find())
                    .sorted(Comparator.<OriginalPart>comparingInt(candidate -> headingPriority(area, candidate.part().text()))
                            .thenComparingInt(OriginalPart::order)).toList();
            for (OriginalPart original : selected) {
                Part part = original.part();
                Evidence evidence = original.evidence();
                String identity = area + "\n" + evidence.url() + "\n" + part.location() + "\n" + part.text();
                if (!seen.add(identity)) continue;
                String id = "source-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
                sections.add(new Section(id, area, part.location(), part.text(), evidence));
                if (++count == (area == Area.REFERENCE ? 10 : MAX_SECTIONS_PER_AREA)) break;
            }
        }
        if (sections.isEmpty()) return Information.pending(snapshot.noticeId());
        List<Area> pending = GUIDANCE_AREAS.stream().filter(area -> sections.stream().noneMatch(section -> section.area() == area)).toList();
        return new Information(snapshot.noticeId().toString(), snapshot.checkedAt(), State.AVAILABLE, sections, pending);
    }

    private static boolean trusted(Source source, Set<String> allowedHosts) {
        if (source == null || !"EXTRACTED".equals(source.status()) || source.sha256() == null
                || !source.sha256().matches("[a-fA-F0-9]{64}") || source.label() == null || source.label().isBlank()
                || source.url() == null) return false;
        try {
            EvidenceValidation.validateUrl(source.url());
            return allowedHosts.contains(URI.create(source.url()).getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean completePart(Part part) {
        return part != null && part.location() != null && !part.location().isBlank()
                && part.text() != null && !part.text().isBlank() && part.text().length() <= MAX_PART_CHARACTERS
                && !part.text().contains("[발췌 길이 제한]");
    }

    private static Pattern category(Area area) {
        return switch (area) {
            case CONDITION -> CONDITION;
            case DOCUMENT -> DOCUMENT;
            case SCHEDULE -> SCHEDULE;
            case REFERENCE -> throw new IllegalArgumentException("Drawings are selected by attachment provenance");
        };
    }

    private static Pattern heading(Area area) {
        return switch (area) {
            case CONDITION -> CONDITION_HEADING;
            case DOCUMENT -> DOCUMENT_HEADING;
            case SCHEDULE -> SCHEDULE_HEADING;
            case REFERENCE -> throw new IllegalArgumentException("Drawings have no qualification headings");
        };
    }

    private static Pattern heading(String terms) {
        return Pattern.compile("(?m)^[\\p{Zs}\\t]*(?:[0-9]+[.)．]|[□■○●▪※①②③④⑤⑥⑦⑧⑨⑩])?[\\p{Zs}\\t]*(?:"
                + terms + ")(?:[\\s:：().]|$)");
    }

    private static int headingPriority(Area area, String text) {
        if (area == Area.REFERENCE) return 2;
        Pattern numbered = switch (area) {
            case CONDITION -> CONDITION_NUMBERED;
            case DOCUMENT -> DOCUMENT_NUMBERED;
            case SCHEDULE -> SCHEDULE_NUMBERED;
            case REFERENCE -> throw new IllegalArgumentException("Drawings have no qualification headings");
        };
        if (numbered.matcher(text).find()) return 0;
        return heading(area).matcher(text).find() ? 1 : 2;
    }

    private static Pattern numberedHeading(String terms) {
        return Pattern.compile("(?m)^[\\p{Zs}\\t]*[0-9]+[.)．][\\p{Zs}\\t]*(?:" + terms + ")[\\p{Zs}\\t]*\\r?$");
    }
}
