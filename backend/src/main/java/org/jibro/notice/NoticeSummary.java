package org.jibro.notice;

import java.util.List;
import java.util.regex.Pattern;

/** Public summary wording; eligibility rules and source material remain untouched. */
public final class NoticeSummary {
    private NoticeSummary() {}

    private static final List<String> PROCESSING_PREFIXES = List.of(
        "공식 목록에서 자동 수집했습니다.",
        "공식 목록 정보입니다.",
        "공식 모집공고의 기본 정보입니다.",
        "공식 원문의 신청 기준·순위·예외와 제출 대상을 함께 확인하는 준비 안내입니다.");

    private static final Pattern CHILDREN_AUDIENCE = Pattern.compile(
        "^미성년\\s+직계비속\\s*\\(태아\\s*포함\\)(?:을)?\\s+(\\d+)명\\s+이상(?:을)?\\s+양육(하는|하며|하고)");
    private static final Pattern MARKET_RENT_BENEFIT = Pattern.compile(
        "주택을 자격에 따라 시중\\s*시세의 (\\d+(?:\\.\\d+)?(?:[~∼～–-]\\d+(?:\\.\\d+)?)?%) 수준의 (임대료로|임대조건으로) 공급합니다\\.");

    /** User-approved short overview wording, not a substitute for the precise conditions. */
    public static String concise(String summary) {
        if (summary == null) return null;
        String text = conciseAudience(summary);
        text = MARKET_RENT_BENEFIT.matcher(text).replaceAll("주택을 시세의 $1 수준의 $2 공급합니다.");
        return text.replace("을 보증금과 월 임대료를 내고 거주할 수 있도록 공급합니다.",
                "을 보증금과 월 임대료로 공급합니다.")
            .replace("을 공고에서 정한 보증금과 월 임대료로 거주할 수 있도록 공급합니다.",
                "을 보증금과 월 임대료로 공급합니다.");
    }

    private static String conciseAudience(String summary) {
        var match = CHILDREN_AUDIENCE.matcher(summary);
        if (!match.find()) return summary;
        String ending = switch (match.group(2)) {
            case "하는" -> "인";
            case "하며" -> "이며";
            default -> "이고";
        };
        return match.group(1) + "자녀 이상" + ending + summary.substring(match.end());
    }

    public static String display(String title, String agency, String summary) {
        if (summary != null && !summary.isBlank()
                && PROCESSING_PREFIXES.stream().noneMatch(summary.strip()::startsWith)) return concise(summary);
        // Do not infer eligibility, benefits or monetary limits from the housing type or title.
        return agency + "의 ‘" + title + "’ 공고입니다.";
    }
}
