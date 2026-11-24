package org.jibro.collection.review;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A deliberately closed grammar. Unknown prose is not a weaker form of a recognized rule.
 */
final class GuidanceRuleGrammar {
    private GuidanceRuleGrammar() {
    }

    record Rule(String kind, String key, String value, String title, List<String> items) {
        String conditionSummary(String source) {
            return switch (key) {
                case "housing" -> "신청자 본인이 집을 소유하지 않아야 해요.";
                case "marriage" -> "신청자는 미혼이어야 해요.";
                case "age" -> "신청할 수 있는 나이는 만 " + value.replace(":", "세 이상, 만 ") + "세 이하예요.";
                case "income", "assets", "vehicle" -> source.replace("신청자의 ", "신청자 본인의 ")
                    .replace("월평균 소득", "한 달 평균 소득").replace("자동차 가액", "자동차의 평가 금액")
                    .replace("이하이어야 합니다.", "이하여야 해요.");
                default -> source;
            };
        }
    }

    private static final Pattern AGE = Pattern.compile("신청자는 만 (\\d{1,3})세 이상 (\\d{1,3})세 이하이어야 합니다\\.");
    private static final Pattern MONEY = Pattern.compile("신청자의 (월평균 소득|총자산|자동차 가액)은 ([0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)원 이하이어야 합니다\\.");
    private static final Pattern DOCUMENTS = Pattern.compile("(공통|대학생|취업준비생) 제출서류: (.+)");
    private static final String DAY = "(\\d{4}[.-]\\d{1,2}[.-]\\d{1,2})";
    private static final Pattern PERIOD = Pattern.compile("(?:신청|접수) ?기간: " + DAY + "\\s*[~∼–]\\s*" + DAY);
    private static final Pattern EVENT = Pattern.compile("(결과 발표|서류 마감): " + DAY);
    private static final List<String> DOCUMENT_NAMES = List.of("주민등록등본", "주민등록초본", "가족관계증명서", "개인정보 수집·이용 동의서",
        "금융정보 제공 동의서", "재학증명서", "졸업증명서", "소득금액증명", "건강보험자격득실확인서");

    static Rule parse(String raw) {
        String text = raw.replaceAll("[\\p{Zs}\\t]+", " ").trim();
        if (text.equals("신청자는 무주택자이어야 합니다."))
            return new Rule("CONDITION", "housing", "NONE", "무주택자에 해당하나요?", List.of());
        if (text.equals("신청자는 미혼이어야 합니다."))
            return new Rule("CONDITION", "marriage", "UNMARRIED", "미혼에 해당하나요?", List.of());
        var age = AGE.matcher(text);
        if (age.matches()) {
            int min = Integer.parseInt(age.group(1)), max = Integer.parseInt(age.group(2));
            if (min > max || max > 120) return null;
            return new Rule("CONDITION", "age", min + ":" + max, "만 " + min + "세 이상 " + max + "세 이하인가요?", List.of());
        }
        var money = MONEY.matcher(text);
        if (money.matches()) {
            var amount = new BigDecimal(money.group(2).replace(",", ""));
            if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("9007199254740991")) > 0) return null;
            String kind = switch (money.group(1)) {
                case "월평균 소득" -> "income";
                case "총자산" -> "assets";
                default -> "vehicle";
            };
            return new Rule("CONDITION", kind, amount.toPlainString(), "신청자 본인의 " + money.group(1) + "이 " + money.group(2) + "원 이하인가요?", List.of());
        }
        var documents = DOCUMENTS.matcher(text);
        if (documents.matches()) {
            var items = List.of(documents.group(2).split(",\\s*"));
            if (items.isEmpty() || items.size() != items.stream().distinct().count() || !DOCUMENT_NAMES.containsAll(items))
                return null;
            String key = switch (documents.group(1)) {
                case "공통" -> "common";
                case "대학생" -> "student";
                default -> "job-seeker";
            };
            return new Rule("DOCUMENT", key, String.join(",", items.stream().sorted().toList()), documents.group(1), items);
        }
        var item = OfficialGuidanceItem.parse(text);
        if (item != null) return item;
        var period = PERIOD.matcher(text);
        if (period.matches()) {
            var from = day(period.group(1));
            var through = day(period.group(2));
            if (from == null || through == null || from.isAfter(through)) return null;
            return new Rule("SCHEDULE", "application", from + ":" + through, "신청 기간", List.of(from.toString(), through.toString()));
        }
        var event = EVENT.matcher(text);
        if (event.matches()) {
            var date = day(event.group(2));
            if (date == null) return null;
            return new Rule("SCHEDULE", event.group(1).equals("결과 발표") ? "announcement" : "documents-due", date.toString(), event.group(1), List.of(date.toString()));
        }
        return null;
    }

    private static LocalDate day(String value) {
        try {
            var parts = value.split("[.-]");
            var date = LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            return date.getYear() < 1900 ? null : date;
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
