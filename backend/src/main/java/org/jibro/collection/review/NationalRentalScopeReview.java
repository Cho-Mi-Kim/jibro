package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Evidence;

/** Keeps unresolved application scope visible when a waiver notice retains standard document rows. */
public final class NationalRentalScopeReview {
    private NationalRentalScopeReview() {}

    private static final Pattern INITIAL_INCOME_WAIVER = Pattern.compile(
        "(?:입주자격완화주요내용|금회.{0,100}모집|소득및자산보유기준|최초(?:입주|모집)).{0,800}소득요건배제");
    private static final Pattern FIRST_COME_WITHOUT_SCORING = Pattern.compile(
        "(?:순위|배점).{0,40}(?:상관없이|관계없이|무관하게).{0,40}(?:접수순|신청접수순|선착순)");
    private static final Pattern INCOME_HOUSEHOLD_DOCUMENT = Pattern.compile(
        "(?:(?:태아|양자).{0,80}월평균소득기준.{0,30}가구원수.{0,120}(?:임신진단서|입양관계증명서)"
            + "|(?:임신진단서|입양관계증명서).{0,100}(?:태아|양자).{0,80}월평균소득기준.{0,30}가구원수)");
    private static final Pattern SCORING_DOCUMENT = Pattern.compile(
        "(?:가족관계증명서.{0,350}(?:육아지원세대배점|사회취약계층가산점)을받고자"
            + "|(?:육아지원세대배점|사회취약계층가산점)을받고자.{0,350}가족관계증명서)");
    private static final Pattern EXPLICIT_DOCUMENT_EXEMPTION = Pattern.compile(
        "^(?:임신진단서|입양관계증명서|가족관계증명서)(?:[/·및]*(?:임신진단서|입양관계증명서))*"
            + "(?:는|은)?(?:금회(?:는)?|이번모집에서는)?(?:제출불필요|제출하지않아도됨|제출대상에서제외)");

    /** Findings request source review; their absence does not establish complete source coverage. */
    public static List<GuidanceFinding> inspect(Source source, String checkedAt) {
        if (source == null || !"EXTRACTED".equals(source.status()) || source.context().isEmpty()) return List.of();
        var findings = new ArrayList<GuidanceFinding>();
        boolean incomeWaived = source.context().stream().map(Part::text).map(NationalRentalScopeReview::compact)
            .anyMatch(text -> INITIAL_INCOME_WAIVER.matcher(text).find());
        boolean firstCome = source.context().stream().map(Part::text).map(NationalRentalScopeReview::compact)
            .anyMatch(text -> FIRST_COME_WITHOUT_SCORING.matcher(text).find());

        if (incomeWaived) {
            Part part = firstMatch(source, INCOME_HOUSEHOLD_DOCUMENT);
            if (part != null) findings.add(new GuidanceFinding("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED",
                "최초 모집은 소득요건을 배제하지만 임신·입양 서류의 제출대상은 소득기준 가구원수 적용자로 남아 있습니다. "
                    + "임신·입양 여부만으로 필요 또는 불필요를 정하지 않고 이번 모집의 제출대상을 확인해야 합니다.",
                evidence(source, part, checkedAt, "임신진단서", "입양관계증명서")));
        }
        if (firstCome) {
            Part part = firstMatch(source, SCORING_DOCUMENT);
            if (part != null) findings.add(new GuidanceFinding("FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED",
                "선정은 순위·배점과 무관한 접수 순번이지만 가족관계 서류에는 배점·가산점 신청 조건이 남아 있습니다. "
                    + "일반 가족관계 증빙과 가산점용 제출대상을 구분하여 확인해야 합니다.",
                evidence(source, part, checkedAt, "가족관계증명서")));
        }
        return List.copyOf(findings);
    }

    private static Part firstMatch(Source source, Pattern pattern) {
        for (Part part : source.context()) {
            String text = compact(part.text());
            var matcher = pattern.matcher(text);
            while (matcher.find()) {
                // An explicit exemption in this document row already resolves its applicability.
                String matched = matcher.group();
                int lastDocument = Math.max(matched.lastIndexOf("임신진단서"),
                    Math.max(matched.lastIndexOf("입양관계증명서"), matched.lastIndexOf("가족관계증명서")));
                String tail = text.substring(matcher.start() + Math.max(0, lastDocument),
                    Math.min(text.length(), matcher.end() + 80));
                if (!EXPLICIT_DOCUMENT_EXEMPTION.matcher(tail).find()) return part;
            }
        }
        return null;
    }

    private static Evidence evidence(Source source, Part part, String checkedAt, String... terms) {
        String anchor = part.text().lines().filter(line -> java.util.Arrays.stream(terms)
            .anyMatch(term -> compact(line).contains(compact(term)))).findFirst().orElse("");
        return new Evidence(source.label() + " · " + part.location(), EvidenceLocationUrl.forSource(source, part.location(), anchor), checkedAt);
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", "");
    }
}
