package org.jibro.notice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jibro.collection.review.ai.GuideReviewClient;
import org.jibro.notice.HousingModels.Guide;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.GuideContentReviewService.SourceChange;

/**
 * Selects exact, local context before invoking the optional language model.
 */
record GuideContentReviewPlan(Guide guide, List<SourceChange> sources, List<Excerpt> excerpts, String payloadJson) {
    static final String POLICY = "guide-faq-update-v1";
    private static final Pattern TERMS = Pattern.compile("[가-힣A-Za-z]{2,}|[0-9][0-9:,./~-]*");
    private static final Pattern EMBEDDED_INSTRUCTION = Pattern.compile("(?is)(?:ignore|disregard|override).{0,60}(?:previous|above|system|instruction)"
        + "|(?:system|developer|assistant)\\s*(?:message|prompt|instruction)"
        + "|(?:이전|위의|시스템|개발자).{0,25}(?:지시|명령|프롬프트).{0,20}(?:무시|대체|따르)"
        + "|(?:api[_ -]?key|secret key|인증키).{0,40}(?:출력|공개|보내|reveal|output)");
    private static final Set<String> GENERIC = Set.of("공고", "신청", "확인", "안내", "해요", "기준", "경우", "있어요", "없어요",
        "해당", "따라요", "또는", "대한", "위한", "다른", "수도", "등의", "있는", "하는", "해당하는", "필요한", "모든");

    record Excerpt(String sourceId, String url, String sha256, List<String> previous, List<String> current) {
    }

    static GuideContentReviewPlan create(Guide guide, List<SourceChange> sources, ObjectMapper json) {
        if (guide == null || guide.id() == null || guide.id().isBlank() || guide.title() == null || guide.body() == null
            || guide.scope() == null || guide.body().length() + guide.scope().length() > 6000
            || sources == null || sources.isEmpty() || sources.size() > 8) throw new IllegalArgumentException();
        Set<String> expected = new HashSet<>();
        Stream.concat(Stream.of(guide.evidence()), guide.additionalEvidence().stream()).forEach(evidence -> {
            if (evidence == null || !GuideSourcePolicy.isOfficialUrl(evidence.url()))
                throw new IllegalArgumentException();
            expected.add(canonical(evidence.url()));
        });
        Set<String> ids = new HashSet<>(), urls = new HashSet<>();
        Set<String> terms = terms(guide.title() + " " + guide.body() + " " + guide.scope());
        var excerpts = new ArrayList<Excerpt>();
        for (var source : sources) {
            if (source == null || source.sourceId() == null || !source.sourceId().matches("[A-Za-z0-9_-]{1,160}")
                || !ids.add(source.sourceId()) || !GuideSourcePolicy.isOfficialUrl(source.url()) || !urls.add(canonical(source.url()))
                || source.sha256() == null || !source.sha256().matches("[a-fA-F0-9]{64}")
                || source.currentText() == null || source.currentText().isBlank() || source.currentText().length() > 200000
                || source.previousText() == null || source.previousText().length() > 200000)
                throw new IllegalArgumentException();
            var previous = lines(source.previousText());
            var current = lines(source.currentText());
            var currentExcerpts = select(current, previous, terms);
            var previousExcerpts = previous.isEmpty() ? List.<String>of() : select(previous, current, terms);
            excerpts.add(new Excerpt(source.sourceId(), source.url(), source.sha256(), previousExcerpts, currentExcerpts));
        }
        // Cross-source conflicts cannot be checked if the caller omits one of this FAQ's sources.
        if (!urls.equals(expected)) throw new IllegalArgumentException();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("policy", POLICY);
        payload.put("guide", Map.of("id", guide.id(), "category", guide.category(), "title", guide.title(),
            "body", guide.body(), "scope", guide.scope()));
        payload.put("sources", excerpts);
        String text = json.writeValueAsString(payload);
        if (text.length() > GuideReviewClient.MAX_PACKET_CHARACTERS) throw new IllegalArgumentException();
        return new GuideContentReviewPlan(guide, List.copyOf(sources), List.copyOf(excerpts), text);
    }

    String fingerprint(String model) {
        return digest(POLICY + "\n" + model + "\n" + payloadJson);
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<String> lines(String value) {
        return Stream.of(value.split("\\R+|(?<=[다요]\\.)\\s+"))
            .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> select(List<String> text, List<String> other, Set<String> terms) {
        if (text.isEmpty()) throw new IllegalArgumentException();
        var counterpart = new HashSet<>(other);
        var ranked = new ArrayList<Integer>();
        var changed = new ArrayList<Integer>();
        for (int i = 0; i < text.size(); i++) {
            if (score(text.get(i), terms) == 0) continue;
            ranked.add(i);
            if (!other.isEmpty() && !counterpart.contains(text.get(i))) changed.add(i);
        }
        if (ranked.isEmpty() || changed.size() > 8) throw new IllegalArgumentException();
        ranked.sort(Comparator.<Integer>comparingInt(index -> score(text.get(index), terms)).reversed().thenComparingInt(Integer::intValue));
        var selected = new LinkedHashSet<Integer>();
        for (int index : changed) addContext(selected, index, text.size());
        for (int index : ranked.subList(0, Math.min(4, ranked.size()))) addContext(selected, index, text.size());
        // Never trim a large paragraph or silently omit a changed relevant clause to fit a request.
        var result = selected.stream().sorted().map(text::get).toList();
        if (result.stream().anyMatch(value -> value.length() > 3000)
            || result.stream().anyMatch(value -> EMBEDDED_INSTRUCTION.matcher(value).find())
            || result.stream().mapToInt(String::length).sum() > 6000) throw new IllegalArgumentException();
        return result;
    }

    private static void addContext(Set<Integer> selected, int index, int size) {
        for (int i = Math.max(0, index - 1); i <= Math.min(size - 1, index + 1); i++) selected.add(i);
    }

    private static Set<String> terms(String value) {
        var result = new HashSet<String>();
        var matcher = TERMS.matcher(value);
        while (matcher.find()) {
            String term = matcher.group().toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("(?:에서는|에서|으로|에게|에는|이라면|이면|이거나|이나|거나|은|는|을|를|의|와|과|도|에|이|가)$", "");
            if (term.length() >= 2 && !GENERIC.contains(term)) result.add(term);
        }
        return result;
    }

    private static int score(String line, Set<String> terms) {
        String text = line.toLowerCase(java.util.Locale.ROOT);
        return (int) terms.stream().filter(text::contains).count();
    }

    private static String canonical(String value) {
        URI uri = URI.create(value);
        return value.substring(0, value.length() - (uri.getRawFragment() == null ? 0 : uri.getRawFragment().length() + 1));
    }
}
