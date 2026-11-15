package org.jibro.collection.review;

import java.util.*;
import java.util.stream.Collectors;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.*;

/** Only differences fully explained by exact, located claims can bypass whole-source disagreement. */
final class ScopedEvidenceConflicts {
    private record Claim(String value, String statement, Evidence evidence) {}
    private final Map<String,List<Claim>> claims = new LinkedHashMap<>();

    void add(GuidanceRuleGrammar.Rule rule, String statement, Evidence evidence) {
        claims.computeIfAbsent(rule.key(), ignored -> new ArrayList<>()).add(new Claim(rule.value(), statement, evidence));
    }

    private Map<String,List<Claim>> conflicting() {
        return claims.entrySet().stream().filter(e -> e.getValue().stream().map(Claim::value).distinct().count() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue,(a,b)->a,LinkedHashMap::new));
    }

    boolean affects(String text) {
        return conflicting().values().stream().flatMap(List::stream).anyMatch(c -> contains(text,c.statement()));
    }

    boolean hasUnmappedDocumentDependency(String text) {
        String body = compact(text);
        var keywords = Map.of("income","소득", "assets","자산", "vehicle","자동차|차량", "age","연령|나이|세이상|세이하",
            "housing","무주택|주택소유", "marriage","혼인|미혼|배우자");
        return conflicting().keySet().stream().anyMatch(key -> java.util.regex.Pattern.compile(
            keywords.getOrDefault(key,"기준")+"|위기준|앞의기준|해당조건|자격충족").matcher(body).find());
    }

    List<String> partition(String text) {
        var parts = new ArrayList<String>();
        var normal = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (affects(line)) {
                if (!normal.toString().isBlank()) parts.add(normal.toString().strip());
                normal.setLength(0);
                parts.add(line);
            } else normal.append(line).append('\n');
        }
        if (!normal.toString().isBlank()) parts.add(normal.toString().strip());
        return parts;
    }

    boolean explainsEverySourceDifference(List<Source> sources, Set<String> documentKeys) {
        var keys = conflicting().keySet();
        if (keys.isEmpty() && documentKeys.isEmpty()) return false;
        var bodies = new HashSet<String>();
        for (Source source : sources) {
            var text = new StringBuilder();
            for (var part : source.context()) for (String line : part.text().split("\\R")) {
                var rule = GuidanceRuleGrammar.parse(line.trim());
                boolean explained = rule != null && (rule.kind().equals("CONDITION") && keys.contains(rule.key())
                    || rule.kind().equals("DOCUMENT") && documentKeys.contains(rule.key()));
                text.append(explained ? "[conflicting-"+rule.kind()+":"+rule.key()+"]" : line).append('\n');
            }
            bodies.add(compact(text.toString()));
        }
        return bodies.size() == 1;
    }

    List<ReviewIssue> bind(List<Question> questions) {
        var result = new ArrayList<ReviewIssue>();
        for (var entry : conflicting().entrySet()) {
            var group = entry.getValue();
            var ids = questions.stream().filter(q -> group.stream().anyMatch(c -> contains(q.reason(),c.statement())))
                .map(Question::id).distinct().toList();
            if (ids.isEmpty()) continue;
            String detail = "공식 자료에 서로 다른 기준이 있습니다. 아래 항목은 어느 한쪽을 확정하지 않고 기관 확인이 필요합니다.\n"
                + group.stream().map(Claim::statement).distinct().collect(Collectors.joining("\n"));
            result.add(new ReviewIssue("e-conflict-"+entry.getKey(),"CONDITION","공식 신청 기준 확인 필요",
                detail,ids,List.of(),group.stream().map(Claim::evidence).distinct().toList()));
        }
        return List.copyOf(result);
    }

    boolean fullyBound(List<ReviewIssue> issues) {
        return issues.size() == conflicting().size() && issues.size() <= 30
            && issues.stream().allMatch(i -> i.detail().length() <= 3000 && i.evidence().size() <= 10);
    }

    private static boolean contains(String text,String statement) { return compact(text).contains(compact(statement)); }
    private static String compact(String text) { return text.replaceAll("[\\s\\p{Zs}]+", ""); }
}
