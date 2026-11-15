package org.jibro.notice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.text.Normalizer;
import org.jibro.notice.HousingModels.Guide;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class GuideService {
    private final Environment env;
    private final GuideRepository repository;

    public GuideService(Environment env, GuideRepository repository) {
        this.env = env;
        this.repository = repository;
    }

    public Map<String, Object> meta() {
        var result = new HashMap<String, Object>();
        result.put("sampleData", false);
        result.put("trialAvailable", env.matchesProfiles("local-sample"));
        if (env.matchesProfiles("local-sample"))
            repository.findTrialNotice().ifPresent(id -> result.put("trialNoticeId", id));
        return result;
    }

    public record GuidePage(List<Guide> items, int page, boolean hasMore) {}
    private static final int PAGE_SIZE = 20;
    private static final int SOURCE_BATCH = 32;
    private static final List<String> CATEGORIES = List.of("지원제도 알아보기", "조건과 용어", "서류 발급 안내", "신청 절차", "공식 문의처");

    public GuidePage guides(String category, String keyword, int page) {
        if (page < 0 || page > 10000 || keyword.length() > 200 || !category.isEmpty() && !CATEGORIES.contains(category))
            throw new IllegalArgumentException();
        var terms = List.of(normalize(keyword).strip().split("\\s+"));
        String generatedText = String.join(" ", CATEGORIES) + " 문의 경로 · 공고 원문의 담당 기관과 문의 항목을 확인하세요. null";
        // Narrow database candidates only with source-derived tokens. Final matching remains on each guide.
        var sourceTerms = terms.stream().filter(term -> !term.isEmpty() && !generatedText.contains(term)
            && !term.contains("\"") && !term.contains("\\")).toList();
        boolean verifiedOnly = List.of("조건과 용어", "서류 발급 안내", "신청 절차").contains(category);
        var result = new ArrayList<Guide>();
        int skip = page * PAGE_SIZE;
        // Bound both source hydration and browser rendering; never materialize the full catalogue.
        for (int offset = 0; ; offset += SOURCE_BATCH) {
            var sources = repository.findPublishedSources(offset, SOURCE_BATCH, verifiedOnly, sourceTerms);
            for (var source : sources) {
                for (var guide : entries(source)) {
                    if (!category.isEmpty() && !guide.category().equals(category)) continue;
                    String text = normalize(guide.category() + " " + guide.title() + " " + guide.body() + " "
                        + (guide.evidence() == null ? "" : guide.evidence().label()));
                    if (!terms.stream().allMatch(text::contains)) continue;
                    if (skip > 0) { skip--; continue; }
                    if (result.size() == PAGE_SIZE) return new GuidePage(List.copyOf(result), page, true);
                    result.add(guide);
                }
            }
            if (sources.size() < SOURCE_BATCH) return new GuidePage(List.copyOf(result), page, false);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static List<Guide> entries(GuideRepository.Source source) {
        var result = new ArrayList<Guide>();
            var id = source.id();
            var title = source.title();
            var content = source.content();
            result.add(new Guide(id + "-summary", "지원제도 알아보기", title, content.summary(), content.evidence()));
            if (content.evidence() != null && content.evidence().url() != null)
                result.add(new Guide(id + "-contact", "공식 문의처", source.agency() + " 문의 경로", title + " · 공고 원문의 담당 기관과 문의 항목을 확인하세요.", content.evidence()));
            if (!source.verified()) return result;
            for (var question : content.questions()) {
                if (question.reviewRequired()) continue;
                result.add(new Guide(id + "-q-" + question.id(), "조건과 용어", question.title(), title + " · " + question.reason(), question.evidence()));
            }
            for (var doc : content.documents()) {
                if (GuidanceIssues.documentRequiresReview(content.reviewIssues(), doc.id(), doc.conditionQuestionId())) continue;
                result.add(new Guide(id + "-d-" + doc.id(), "서류 발급 안내", doc.title(), title + " · " + doc.issuer() + " · " + doc.method(), doc.evidence()));
            }
            for (var step : content.schedules())
                result.add(new Guide(id + "-s-" + step.id(), "신청 절차", step.title(), title + " · " + step.date(), step.evidence()));
        return result;
    }
}
