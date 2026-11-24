package org.jibro.notice;

import java.util.List;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Question;

/**
 * Read-only overview of reviewed guidance, never an applicant eligibility/rank calculation.
 */
public final class NoticeQualificationSummary {
    private NoticeQualificationSummary() {
    }

    public record Overview(String eligibility, String selection, String detail, Evidence evidence,
                           boolean reviewRequired) {
    }

    private static final Pattern SENTENCE = Pattern.compile("(?<=[다요][.!?])\\s+");
    private static final Pattern RANK = Pattern.compile("[1-9]\\s*순위");
    private static final Pattern SELECTION_HEADING = Pattern.compile(
            "^(?:\\d+\\s*[.)]\\s*)?(?:선정\\s*기준|입주자\\s*선정\\s*(?:기준|순위)|선정\\s*순위)\\s*\\r?\\n");
    private static final Pattern REGIONAL = Pattern.compile(
            "^공고일 [\\d.]+ 주민등록을 기준으로 타지역 거주자를 먼저 경합·선정하고, 선순위 미달 때 같은 사업지역\\(([^()]+)\\) 거주자를 포함한 후순위를 진행합니다\\.$");

    public static Overview from(String summary, List<Question> questions) {
        String audience = summary == null ? "" : SENTENCE.split(NoticeSummary.concise(summary))[0];
        if (!audience.matches(".*대상(?:입니다|이며).*")) {
            audience = "신청 자격은 조건 탭에서 항목별로 확인해 주세요.";
        }
        boolean needsReview = false;
        if (questions != null) {
            for (Question question : questions) {
                if (question == null || question.reason() == null) continue;
                for (String sentence : SENTENCE.split(question.reason().strip())) {
                    String selection = selection(sentence, question.reason());
                    if (selection == null) continue;
                    if (Boolean.TRUE.equals(question.reviewRequired())) {
                        needsReview = true;
                        continue;
                    }
                    // Publish only wording that retains a usable official source.
                    if (question.evidence() == null || question.evidence().url() == null
                            || question.evidence().url().isBlank()) continue;
                    return new Overview(audience, selection, question.reason(), question.evidence(), false);
                }
            }
        }
        return new Overview(audience, needsReview ? "선정 기준은 추가 확인이 필요합니다."
                : "선정 기준은 조건 탭과 공식 공고에서 확인해 주세요.", null, null, needsReview);
    }

    private static String selection(String sentence, String context) {
        // Extracted sections retain their heading. Remove only that known heading, not table rows or qualifiers.
        sentence = SELECTION_HEADING.matcher(sentence).replaceFirst("").strip();
        var regional = REGIONAL.matcher(sentence);
        if (regional.matches()) {
            return regional.group(1) + " 외 거주자 우선, 미달 시 " + regional.group(1) + " 거주자를 포함합니다.";
        }
        // These complete clauses are already reviewed; keep the precise exceptions in detail.
        if (sentence.startsWith("1순위는 신생아가구 또는 법정 보호대상 한부모, 2순위는")
                && sentence.contains("3순위는") && sentence.endsWith("4순위는 6세 이하 자녀의 혼인가구입니다.")) {
            return "신생아·법정 보호대상 한부모가 1순위입니다. 이후 자녀 유무·가구 유형에 따라 2~4순위를 정합니다.";
        }
        boolean ranks = RANK.matcher(sentence).results().map(match -> match.group()).distinct().count() >= 2
                && (sentence.endsWith("순위입니다.") || sentence.startsWith("1순위는 "));
        boolean ordered = sentence.contains("→") && sentence.contains("추첨")
                && (sentence.endsWith("순서입니다.") || sentence.endsWith("입니다."));
        boolean scoring = sentence.startsWith("같은 순위에서는") && sentence.endsWith("순으로 선정합니다.");
        boolean priority = sentence.startsWith("동일순위에서") && sentence.endsWith("거주자를 우선합니다.");
        boolean arrival = sentence.matches(".*도착자 경합은 추첨, 이후는 도착순입니다\\.")
                || sentence.startsWith("이번 선정은 거주지역·순위·배점과 무관한 접수 순번이며");
        // Do not guess from notice titles, document-proof questions, tables or fragments.
        if (!(ranks || ordered || scoring || priority || arrival) || sentence.length() > 220
                || sentence.contains("\n")) return null;
        if (sentence.startsWith("두 유형 모두 ")) {
            return context.contains("일반공급은") && context.contains("주거약자형은")
                    ? sentence.replace("두 유형 모두 ", "일반공급·주거약자형 모두 ") : null;
        }
        return sentence;
    }
}
