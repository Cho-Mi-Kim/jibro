package org.jibro.notice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.Guide;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.GuideContentReviewService.Status;

/**
 * A model cannot publish, change links, erase exceptions, or introduce unsupported quantities.
 */
final class GuideContentReviewValidator {
    private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:[,.:/-][0-9]+)*(?:%|세|명|년|월|일|시|분|원|배|회)?");
    private static final Pattern UNSUPPORTED_FORMAT = Pattern.compile("(?im)(?:https?://|www\\.|<[^>]+>|\\]\\(|\\*|__|`|\\||^\\s*[#>+]|\\[[^\\]]+\\]:)");
    private static final Pattern WORDS = Pattern.compile("[가-힣A-Za-z]{2,}");
    private static final List<String> PROTECTED = List.of("예외", "제외", "미만", "이하", "이상", "초과", "최대", "최소", "무주택",
        "신혼", "청년", "대학생", "부모", "배우자", "자녀", "본인", "대리", "태아", "입양", "공고", "상세", "일반", "필수",
        "온라인", "방문", "평일", "주말", "공휴일", "법정", "증빙", "않", "안 돼", "불가", "없", "별도", "각각",
        "모두", "전원", "또는", "무조건", "반드시", "면제", "세대", "소득", "자산");

    record Proof(String sourceId, String previousQuote, String currentQuote) {
    }

    record Validated(Status status, Guide candidate) {
    }

    static Validated validate(GuideContentReviewPlan plan, String output, ObjectMapper json) {
        if (output == null || output.length() > 20000) throw new IllegalArgumentException();
        var value = json.readTree(output);
        fields(value, Set.of("decision", "reason", "patches", "evidence"));
        String decision = text(value.path("decision"), 30);
        text(value.path("reason"), 500);
        var patches = value.path("patches");
        if (!patches.isArray() || patches.size() > 6) throw new IllegalArgumentException();
        if (decision.equals("REVIEW_REQUIRED")) {
            if (!patches.isEmpty() || !value.path("evidence").isArray()) throw new IllegalArgumentException();
            return new Validated(Status.REVIEW_REQUIRED, null);
        }
        var evidence = proofs(plan, value.path("evidence"));
        Set<String> sourceIds = new HashSet<>();
        evidence.forEach(proof -> sourceIds.add(proof.sourceId()));
        if (!sourceIds.equals(new HashSet<>(plan.sources().stream().map(GuideContentReviewService.SourceChange::sourceId).toList())))
            throw new IllegalArgumentException();
        if (decision.equals("CONFIRMED")) {
            if (!patches.isEmpty()) throw new IllegalArgumentException();
            requireAllQuantitiesSupported(plan.guide().body() + "\n" + plan.guide().scope(), evidence);
            return new Validated(Status.CONFIRMED, plan.guide());
        }
        if (!decision.equals("UPDATED") || patches.isEmpty()) throw new IllegalArgumentException();
        String body = plan.guide().body(), scope = plan.guide().scope();
        var used = new HashSet<String>();
        for (var patch : patches) {
            fields(patch, Set.of("field", "before", "after", "evidence"));
            String field = text(patch.path("field"), 10);
            if (!Set.of("body", "scope").contains(field)) throw new IllegalArgumentException();
            String before = text(patch.path("before"), 1500), after = text(patch.path("after"), 1500);
            if (before.length() < 5 || after.length() < 5 || before.equals(after) || !used.add(field + "\n" + before)
                || UNSUPPORTED_FORMAT.matcher(after).find() || after.codePoints().anyMatch(GuideContentReviewValidator::unsafeControl))
                throw new IllegalArgumentException();
            String original = field.equals("body") ? plan.guide().body() : plan.guide().scope();
            String current = field.equals("body") ? body : scope;
            int at = original.indexOf(before);
            if (at < 0 || original.indexOf(before, at + 1) >= 0 || !wholeLines(original, at, before.length())
                || current.indexOf(before) < 0 || current.indexOf(before, current.indexOf(before) + 1) >= 0)
                throw new IllegalArgumentException();
            var proof = proofs(plan, patch.path("evidence"));
            requirePreservedScope(before, after);
            requireNumbers(before, after, proof);
            requireGroundedWords(before, after, proof);
            String updated = current.replace(before, after);
            if (field.equals("body")) body = updated;
            else scope = updated;
        }
        if (body.length() + scope.length() > 6000) throw new IllegalArgumentException();
        requireAllQuantitiesSupported(body + "\n" + scope, evidence);
        var original = plan.guide();
        return new Validated(Status.UPDATED, new Guide(original.id(), original.category(), original.title(), body,
            original.evidence(), scope, original.additionalEvidence(), original.freshness()));
    }

    private static List<Proof> proofs(GuideContentReviewPlan plan, JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > 12) throw new IllegalArgumentException();
        var result = new ArrayList<Proof>();
        for (var value : values) {
            fields(value, Set.of("sourceId", "previousQuote", "currentQuote"));
            String id = text(value.path("sourceId"), 160), current = text(value.path("currentQuote"), 1500);
            var previousNode = value.path("previousQuote");
            if (!previousNode.isString() || previousNode.asText().length() > 1500 || current.length() < 8)
                throw new IllegalArgumentException();
            String previous = previousNode.asText();
            var source = plan.excerpts().stream().filter(item -> item.sourceId().equals(id)).findFirst().orElseThrow(IllegalArgumentException::new);
            if (source.current().stream().noneMatch(text -> text.contains(current))
                || !previous.isEmpty() && source.previous().stream().noneMatch(text -> text.contains(previous)))
                throw new IllegalArgumentException();
            result.add(new Proof(id, previous, current));
        }
        return List.copyOf(result);
    }

    private static void requirePreservedScope(String before, String after) {
        String old = before.replaceAll("\\s+", ""), next = after.replaceAll("\\s+", "");
        for (String marker : PROTECTED) {
            String token = marker.replace(" ", "");
            // Eligibility, exception and logical scope changes need a human review, even with a plausible quote.
            if (occurrences(old, token) != occurrences(next, token)) throw new IllegalArgumentException();
        }
    }

    private static int occurrences(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private static boolean unsafeControl(int codePoint) {
        return Character.isISOControl(codePoint) && codePoint != '\n'
            || codePoint >= 0x202a && codePoint <= 0x202e || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    private static void requireGroundedWords(String before, String after, List<Proof> evidence) {
        String known = before + " " + String.join(" ", evidence.stream().map(Proof::currentQuote).toList());
        Set<String> knownWords = words(known);
        for (String word : words(after)) {
            if (!knownWords.contains(word)) throw new IllegalArgumentException();
        }
    }

    private static Set<String> words(String value) {
        var result = new HashSet<String>();
        var matcher = WORDS.matcher(value);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("(?:입니다|합니다|이에요|예요|해요|돼요|있어요|에서는|에서|으로|에게|에는|이라면|이면|이거나|이나|거나|부터|까지|은|는|을|를|의|와|과|도|에|이|가)$", "");
            if (word.length() >= 2) result.add(word);
        }
        return result;
    }

    private static void requireNumbers(String before, String after, List<Proof> evidence) {
        Set<String> previous = numbers(before), current = numbers(after);
        for (String number : current) {
            if (previous.contains(number)) continue;
            if (evidence.stream().noneMatch(proof -> numbers(proof.currentQuote()).contains(number)))
                throw new IllegalArgumentException();
        }
        for (String number : previous) {
            if (current.contains(number)) continue;
            boolean replaced = evidence.stream().anyMatch(proof -> numbers(proof.previousQuote()).contains(number)
                && !numbers(proof.currentQuote()).contains(number)
                && current.stream().anyMatch(next -> !previous.contains(next) && numbers(proof.currentQuote()).contains(next)));
            if (!replaced) throw new IllegalArgumentException();
        }
    }

    private static Set<String> numbers(String value) {
        var result = new HashSet<String>();
        var matcher = NUMBERS.matcher(value.replaceAll("(?<=\\d)\\s+(?=(?:세|명|년|월|일|시|분|원|배|회|%))", ""));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static void requireAllQuantitiesSupported(String content, List<Proof> evidence) {
        String withoutListNumbers = content.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        var supported = new HashSet<String>();
        evidence.forEach(proof -> supported.addAll(numbers(proof.currentQuote())));
        if (!supported.containsAll(numbers(withoutListNumbers))) throw new IllegalArgumentException();
    }

    private static boolean wholeLines(String original, int start, int length) {
        int end = start + length;
        return (start == 0 || original.charAt(start - 1) == '\n') && (end == original.length() || original.charAt(end) == '\n');
    }

    private static String text(JsonNode value, int max) {
        if (!value.isString() || value.asText().isBlank() || value.asText().length() > max)
            throw new IllegalArgumentException();
        return value.asText();
    }

    private static void fields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) throw new IllegalArgumentException();
        for (String field : fields) if (!value.has(field)) throw new IllegalArgumentException();
    }
}
