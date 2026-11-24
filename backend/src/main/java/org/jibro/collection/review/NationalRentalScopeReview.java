package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Question;
import org.jibro.notice.HousingModels.AnswerOption;

/**
 * Keeps unresolved application scope visible when a waiver notice retains standard document rows.
 */
public final class NationalRentalScopeReview {
    private NationalRentalScopeReview() {
    }

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

    // Only unconditional recruitment statements can disapply a proof for its stated purpose.
    private static final Pattern WAIVER_LINE = Pattern.compile(
            "(?:입주자격완화주요내용(?:\\(필독\\))?)?(?:(?:금회|이번)모집(?:하는국민임대주택은|에서는)?)?(?:소득요건)?소득요건배제[.]?");
    private static final Pattern ORDER_LINE = Pattern.compile(
            "(?:국민임대주택입주자격을충족하는자에대하여)?(?:거주지역[,·]?)?순위및배점(?:등)?에(?:상관없이|관계없이)"
                    + "신청접수순번대로(?:입주자를)?선정(?:합니다|함|하되,신청자를대상으로주택,소득,총자산등을검색하여적격자에한하여개별적으로계약체결을통보합니다)[.]?");
    private static final Pattern INCOME_PURPOSE = Pattern.compile(
            "(?:태아|양자)(?:또는양자)?를?월평균소득기준의?가구원수에포함(?:하여적용을?받으려는자|하려면)");
    private static final Pattern SCORE_PURPOSE = Pattern.compile(
            "(?:육아지원세대배점|사회취약계층가산점)을받고자(?:하는신청자|할경우)");
    // The PDF text layer may insert the issuer column in the middle of "받으려는".
    // Accept only that positive clause, corroborated by a clean copy in the sibling official HWPX.
    private static final Pattern INTERLEAVED_ISSUER = Pattern.compile(
            "받으(?:의료법에)?의한려는자(?=임신진단서의료기관)");

    static Question explainPurpose(List<Source> sources, Source source, Part row, GuidanceFinding finding) {
        boolean income = finding.code().equals("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED");
        if (!income && !finding.code().equals("FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED")) return null;
        String rowText = compact(row.text());
        if (!(income ? INCOME_PURPOSE : SCORE_PURPOSE).matcher(rowText).find()) {
            if (!income || !source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                    || !INCOME_PURPOSE.matcher(INTERLEAVED_ISSUER.matcher(rowText).replaceAll("받으려는자")).find())
                return null;
            String sibling = source.label().substring(0, source.label().length() - 4) + ".hwpx";
            if (sources.stream().filter(s -> sibling.equalsIgnoreCase(s.label()) && "EXTRACTED".equals(s.status())
                            && s.sha256() != null && s.sha256().matches("[a-f0-9]{64}"))
                    .flatMap(s -> s.context().stream()).noneMatch(p -> INCOME_PURPOSE.matcher(compact(p.text())).find()
                            && INCOME_HOUSEHOLD_DOCUMENT.matcher(compact(p.text())).find())) return null;
        }
        var rule = income ? WAIVER_LINE : ORDER_LINE;
        for (Part part : source.context()) {
            String[] lines = part.text().lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("⟦"))
                    .toArray(String[]::new);
            for (int i = 0; i < lines.length; i++) {
                String line = compact(lines[i]).replaceFirst("^[▪•*-]+", "");
                String next = i + 1 < lines.length ? compact(lines[i + 1]) : "";
                boolean joined = !rule.matcher(line).matches() && rule.matcher(line + next).matches();
                if (!joined && !rule.matcher(line).matches()) continue;
                String before = compact(String.join("", java.util.Arrays.copyOfRange(lines, Math.max(0, i - 8), i)));
                if (income && !((before + line).contains("입주자격완화주요내용") || line.startsWith("이번모집에서는"))) continue;
                if (before.substring(Math.max(0, before.lastIndexOf("■"))).matches(".*(?:일부유형|에한하여|제외하고|만해당|단,|다만).*"))
                    continue;
                String following = joined ? (i + 2 < lines.length ? compact(lines[i + 2]) : "") : next;
                if (following.matches("^[▪•※*-]*(?:다만|단[,，:]|단서|예외|제외하고|하는경우에한).*")) continue;
                String title = income ? "소득 계산용 서류가 필요한가요?" : "가점을 받기 위한 서류가 필요한가요?";
                String reason = income ? "이번 모집은 소득을 따지지 않아요. 태아나 입양 자녀를 소득 계산에 포함시키기 위한 증빙은 그 목적으로 낼 필요가 없어요."
                        : "자격을 갖추면 접수한 순서대로 뽑아요. 가점을 받기 위해 가족관계증명서를 낼 필요는 없어요.";
                reason += " 가족관계나 다른 신청 자격을 증명할 때는 해당 서류 안내를 따르세요.";
                return new Question(income ? "auto-income-proof-purpose" : "auto-score-proof-purpose", title, reason,
                        finding.evidence(), List.of(new AnswerOption("YES", "확인했어요"), new AnswerOption("UNKNOWN", "더 확인할게요", true)));
            }
        }
        return null;
    }

    /**
     * Findings request source review; their absence does not establish complete source coverage.
     */
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
