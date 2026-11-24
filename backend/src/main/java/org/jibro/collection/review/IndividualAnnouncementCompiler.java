package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * Reads explicit result-notification clauses, never deriving a calendar date from a duration.
 */
final class IndividualAnnouncementCompiler {
    private static final String RESULT = "(?:입주대상자\\s*선정\\s*안내|(?:예비입주자|입주대상자|당첨자|결과)\\s*(?:확정\\s*)?발표)";
    private static final Pattern DURATION = Pattern.compile("입주대상자\\s*선정\\s*안내\\s*[:：]\\s*"
            + "(?:\\([^\\n)]{1,100}\\)\\s*)?신청일로부터\\s*(?:최소\\s*)?\\d+\\s*(?:주|개월)\\s*소요");
    private static final Pattern DELIVERY = Pattern.compile("대상자\\s*선정\\s*및\\s*소명대상\\s*안내\\s*등은\\s*SMS\\s*및\\s*e-mail을\\s*발송할\\s*예정");
    private static final Pattern SHORTER = Pattern.compile("수급자[^\\n]{0,100}은\\s*입주대상자\\s*선정\\s*기간이\\s*단축될\\s*수\\s*있음");
    private static final Pattern DELAY = Pattern.compile("제출서류\\s*미비\\s*또는\\s*자격검증\\s*소요기간에\\s*따라\\s*발표\\s*일정이\\s*연기될\\s*수\\s*있음");
    private static final Pattern DIRECT = Pattern.compile(RESULT + "\\s*[:：]?\\s*(?:[•·]\\s*발표일\\s*[:：]\\s*)?"
            + "(?:(?:자격검증\\s*결과\\s*)?적격자에\\s*한하여\\s*|자격검증\\s*및\\s*(?:부적격자\\s*)?소명\\s*완료\\s*후\\s*)?개별\\s*(?:문자\\s*)?(?:안내|통보)"
            + "(?:\\s*\\((?:20\\d{2}\\s*[.년]\\s*\\d{1,2}\\s*월\\s*예정|신청일로부터\\s*\\d+\\s*개월\\s*이후\\s*발표가\\s*예상되며\\s*자격검증\\s*일정에\\s*따라\\s*발표일은\\s*변동\\s*가능)\\))?");
    private static final Pattern CHANGED = Pattern.compile("발표는\\s*자격검색\\s*처리\\s*기간에\\s*따라\\s*변동\\s*될\\s*수\\s*있습니다[.。]?");
    private static final Pattern NEGATION = Pattern.compile("개별\\s*(?:안내|통보)\\s*(?:하지|하지는|하지않|없|하지\\s*않)|선정\\s*안내\\s*예시|발췌 길이 제한");

    private IndividualAnnouncementCompiler() {
    }

    static List<ScheduleTemplate> read(List<Source> sources, LocalDate checkedDay) {
        List<ScheduleTemplate> results = new ArrayList<>();
        for (Source source : sources) {
            if (!"EXTRACTED".equals(source.status()) || source.url() == null || source.url().isBlank()
                    || source.sha256() == null || source.sha256().isBlank() || OcrProvenance.contains(source)) continue;
            for (var part : source.context()) {
                if (part.location() == null || part.location().isBlank()) continue;
                for (var finding : org.jibro.collection.review.local.IndividualAnnouncementTableReader.read(source, part)) {
                    add(results, finding.description(), source, finding.location(), checkedDay);
                }
                String text = part.text().replaceAll("[\\p{Zs}\\t]+", " ");
                // Full source context is required. Excerpts alone can omit qualifications or negations.
                var duration = DURATION.matcher(text);
                while (duration.find()) {
                    int end = nextSection(text, duration.end());
                    String context = text.substring(duration.start(), end);
                    if (NEGATION.matcher(context).find() || !DELIVERY.matcher(context).find()) continue;
                    List<String> clauses = new ArrayList<>();
                    clauses.add(duration.group());
                    append(clauses, SHORTER, context);
                    append(clauses, DELAY, context);
                    append(clauses, DELIVERY, context);
                    add(results, String.join("\n", clauses), source, part.location(), checkedDay);
                }
                var direct = DIRECT.matcher(text);
                while (direct.find()) {
                    String following = text.substring(direct.end(), Math.min(text.length(), direct.end() + 30));
                    if (NEGATION.matcher(direct.group() + following).find()) continue;
                    List<String> clauses = new ArrayList<>();
                    clauses.add(direct.group());
                    append(clauses, CHANGED, text.substring(direct.end(), nextSection(text, direct.end())));
                    add(results, String.join("\n", clauses), source, part.location(), checkedDay);
                }
            }
        }
        return List.copyOf(results);
    }

    private static int nextSection(String text, int start) {
        var next = Pattern.compile("(?m)^\\s*(?:[■□]|\\d{1,2}\\.\\s*[가-힣]|⟦HWPX:)").matcher(text);
        return next.find(start) ? next.start() : text.length();
    }

    private static void append(List<String> clauses, Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) clauses.add(matcher.group());
    }

    private static void add(List<ScheduleTemplate> results, String text, Source source, String location, LocalDate checkedDay) {
        String description = text.replaceAll("[\\p{Zs}\\t]+", " ").strip();
        if (description.length() > 1000) return; // Never truncate conditions to fit the public contract.
        results.add(new ScheduleTemplate("auto-individual-result", "결과 발표 · 개별 안내", "",
                new Evidence(location + " · 개별 발표 안내", EvidenceLocationUrl.forExcerpt(source, location, text), checkedDay.toString()), description));
    }
}
