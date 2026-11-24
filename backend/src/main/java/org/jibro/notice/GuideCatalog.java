package org.jibro.notice;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Guide;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GuideCatalog {
    static final List<String> CATEGORIES = List.of("지원제도 알아보기", "조건과 용어", "서류 발급 안내", "신청 절차", "공식 문의처");
    private final List<Guide> items;

    record CatalogFile(String reviewedAt, List<Guide> items) {
    }

    @Autowired
    public GuideCatalog(ObjectMapper json) {
        this(read(json));
    }

    GuideCatalog(CatalogFile source) {
        if (source == null || source.items() == null || source.items().isEmpty())
            throw new IllegalArgumentException("가이드 검토 원고가 비어 있습니다.");
        LocalDate reviewedAt = LocalDate.parse(source.reviewedAt());
        Set<String> ids = new HashSet<>();
        for (var guide : source.items()) {
            if (guide == null || blank(guide.id()) || !ids.add(guide.id()) || !CATEGORIES.contains(guide.category())
                || blank(guide.title()) || blank(guide.body()) || blank(guide.scope()))
                throw new IllegalArgumentException("가이드 항목의 식별자·분류·내용·적용 범위를 확인하세요.");
            Set<String> urls = new HashSet<>();
            Stream.concat(Stream.of(guide.evidence()), guide.additionalEvidence().stream()).forEach(evidence -> {
                validateEvidence(evidence, reviewedAt);
                if (!urls.add(evidence.url())) throw new IllegalArgumentException("중복된 가이드 출처입니다.");
            });
        }
        items = List.copyOf(source.items());
    }

    List<Guide> items() {
        return items;
    }

    private static CatalogFile read(ObjectMapper json) {
        try (var source = new ClassPathResource("guides/guide-catalog.json").getInputStream()) {
            return json.readValue(source, CatalogFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("공식 자료로 검토한 가이드 원고를 읽을 수 없습니다.", e);
        }
    }

    private static void validateEvidence(Evidence evidence, LocalDate reviewedAt) {
        if (evidence == null || blank(evidence.label()) || blank(evidence.url()) || blank(evidence.checkedAt()))
            throw new IllegalArgumentException("가이드 공식 출처와 확인일이 필요합니다.");
        if (!GuideSourcePolicy.isOfficialUrl(evidence.url())
            || LocalDate.parse(evidence.checkedAt()).isAfter(reviewedAt))
            throw new IllegalArgumentException("가이드 공식 출처 주소와 확인일을 확인하세요.");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
