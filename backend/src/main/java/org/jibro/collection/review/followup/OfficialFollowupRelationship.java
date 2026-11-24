package org.jibro.collection.review.followup;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;

import static org.jibro.collection.review.followup.OfficialFollowupModels.Target;

/**
 * Exact recruitment identity checks; lexical search candidates alone never establish a relationship.
 */
final class OfficialFollowupRelationship {
    private static final List<String> HOUSING = List.of("신혼희망타운", "행복주택", "국민임대", "영구임대", "공공임대", "매입임대", "전세임대", "공공분양");
    private static final Pattern DATE = Pattern.compile("(?<![0-9])([0-9]{4}|[‘’'`][0-9]{2})[.년/\\-]\\s*([0-9]{1,2})[.월/\\-]\\s*([0-9]{1,2})(?![0-9])");

    record Identity(List<String> scope, List<String> housing) {
    }

    record Match(String proof, boolean ambiguous) {
    }

    private OfficialFollowupRelationship() {
    }

    static Identity identity(String title) {
        String clean = title.replaceAll("\\[(?:정정|수정|변경|취소|재)?공고\\]", " ")
                .replaceAll("[0-9]{4}[.년/\\-]\\s*[0-9]{1,2}[.월/\\-]\\s*[0-9]{1,2}\\.?일?", " ")
                .replaceAll("(?<![0-9])[0-9]{4}년\\s*", " ");
        var housing = HOUSING.stream().filter(type -> compact(title).contains(type)).toList();
        for (String type : HOUSING) clean = clean.replace(type, " ");
        clean = clean.replaceAll("예비입주자|입주자|입주민|모집|공고|수시|잔여세대|잔여|추가|정정|수정|선착순", " ").replace("LH", " ");
        var scope = new LinkedHashSet<String>();
        for (String word : clean.split("[\\s()\\[\\]·,]+")) {
            String value = compact(word);
            if (value.matches("[가-힣]{2,}시")) value = value.substring(0, value.length() - 1);
            if (value.length() >= 2 && !Set.of("주택", "아파트", "안내", "계약", "신청", "공공", "임대").contains(value))
                scope.add(value);
        }
        return new Identity(List.copyOf(scope), housing);
    }

    static Optional<String> queryTerm(Target target) {
        var identity = identity(target.title());
        if (!identity.housing().isEmpty() && !identity.scope().isEmpty()
                && identity.scope().stream().allMatch(value -> value.matches("청년|신혼부부|전국|[0-9]+(?:순위|차)|수급자|대학생"))) {
            String audience = identity.scope().stream().filter(value -> value.equals("청년") || value.equals("신혼부부")).findFirst().orElse("");
            return Optional.of((audience + " " + identity.housing().get(0)).strip());
        }
        var words = identity.scope().stream().map(value -> value.replaceAll("[^가-힣]", ""))
                .filter(value -> value.length() >= 2 && value.length() <= 30).toList();
        var cities = new HashSet<String>();
        var city = Pattern.compile("(?<![가-힣])([가-힣]{2,})시(?=\\s)").matcher(target.title());
        while (city.find()) cities.add(city.group(1));
        var local = words.stream().filter(value -> !cities.contains(value)
                        && !Set.of("복합타운", "신도시", "지구", "단지", "블록", "스타힐스", "순위").contains(value))
                .max(Comparator.comparingInt(String::length));
        return local.isPresent() ? local : words.stream().max(Comparator.comparingInt(String::length));
    }

    static Match match(Target target, List<Part> parts) {
        var identity = identity(target.title());
        if (target.publishedDate() == null || identity.housing().isEmpty()
                || identity.scope().stream().mapToInt(String::length).sum() < 5) return new Match(null, true);
        if (multipleRecruitments(target, parts)) return new Match(null, true);
        String whole = parts.stream().map(Part::text).reduce("", (a, b) -> a + "\n" + b);
        String normalized = compact(whole);
        if (!identity.scope().stream().allMatch(normalized::contains) || !identity.housing().stream().allMatch(normalized::contains))
            return new Match(null, false);
        boolean otherRecruitment = false;
        for (var part : parts) {
            if (part.location().equals("공식 후속 공지 게시 정보")) continue;
            String normalizedPart = compact(part.text());
            if (!identity.scope().stream().allMatch(normalizedPart::contains) || !identity.housing().stream().allMatch(normalizedPart::contains))
                continue;
            var dates = references(part.text());
            if (dates.contains(target.publishedDate()))
                return new Match(part.location() + "의 원모집 공고일 " + target.publishedDate()
                        + " 및 동일 공급 범위 " + String.join(" · ", identity.scope()) + " / " + String.join(" · ", identity.housing())
                        + " 직접 언급: " + part.text().replaceAll("\\s+", " ").strip().substring(0, Math.min(700, part.text().replaceAll("\\s+", " ").strip().length())), false);
            if (!dates.isEmpty()) otherRecruitment = true;
        }
        return new Match(null, !otherRecruitment);
    }

    /**
     * Scheduling hint only: the full body still has to prove the recruitment relationship.
     */
    static boolean titleReferencesTargetDate(Target target, String title) {
        return target.publishedDate() != null && references(title).contains(target.publishedDate());
    }

    static boolean multipleRecruitments(Target target, List<Part> parts) {
        var dates = new HashSet<LocalDate>();
        parts.stream().filter(part -> !part.location().equals("공식 후속 공지 게시 정보")).forEach(part -> dates.addAll(references(part.text())));
        if (dates.size() > 1) return true;
        String title = parts.isEmpty() ? "" : compact(parts.get(0).text());
        if (title.contains("청년") && title.contains("신혼부부")
                && !(compact(target.title()).contains("청년") && compact(target.title()).contains("신혼부부"))) return true;
        var block = Pattern.compile("[A-Z][0-9]+(?:블록|BL)|[가-힣]+[0-9]+단지");
        var targetBlocks = new HashSet<String>();
        var targetMatches = block.matcher(compact(target.title()));
        while (targetMatches.find()) targetBlocks.add(targetMatches.group());
        var titles = block.matcher(title);
        while (titles.find()) if (!targetBlocks.isEmpty() && !targetBlocks.contains(titles.group())) return true;
        return false;
    }

    static Set<LocalDate> references(String text) {
        var result = new HashSet<LocalDate>();
        var dates = DATE.matcher(text);
        while (dates.find()) {
            String before = text.substring(Math.max(0, dates.start() - 18), dates.start());
            String after = text.substring(dates.end(), Math.min(text.length(), dates.end() + 130));
            // Exact reference wording around this date; not another date anywhere in a nearby row.
            boolean labelled = before.matches("(?s).*(?:공고(?:일)?|모집(?:\\s*공고)?(?:\\s*관련)?)\\s*[:：(‘’'`]*\\s*$")
                    || after.matches("(?s)^[.일)\\s]*(?:모집\\s*)?공고.*")
                    || (!DATE.matcher(after).find() && after.matches("(?s)^[.일)\\s]+[^\\n]{0,100}모집\\s*공고.*"));
            if (!labelled) continue;
            try {
                String year = dates.group(1);
                int value = year.matches("[0-9]{4}") ? Integer.parseInt(year) : 2000 + Integer.parseInt(year.substring(1));
                result.add(LocalDate.of(value, Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3))));
            } catch (RuntimeException invalid) { /* An unparseable date is not evidence of a link. */ }
        }
        return result;
    }

    private static String compact(String value) {
        return value.replace("블럭", "블록").toUpperCase(Locale.ROOT).replaceAll("[^가-힣A-Z0-9]", "");
    }
}
