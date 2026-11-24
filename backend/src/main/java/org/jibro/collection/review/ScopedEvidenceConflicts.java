package org.jibro.collection.review;

import java.util.*;
import java.util.stream.Collectors;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.*;

/**
 * Only differences fully explained by exact, located claims can bypass whole-source disagreement.
 */
final class ScopedEvidenceConflicts {
    private record Claim(String value, String statement, Evidence evidence) {
    }

    private final Set<String> referrals = new LinkedHashSet<>();
    private final Map<String, List<Claim>> claims = new LinkedHashMap<>();

    void add(GuidanceRuleGrammar.Rule rule, String statement, Evidence evidence) {
        if (OfficialGuidanceItem.needsAgency(rule)) referrals.add(rule.key());
        claims.computeIfAbsent(rule.key(), ignored -> new ArrayList<>()).add(new Claim(rule.value(), statement, evidence));
    }

    private Map<String, List<Claim>> conflicting() {
        return claims.entrySet().stream().filter(e -> e.getValue().stream().map(Claim::value).distinct().count() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    boolean affects(String text) {
        var differences = conflicting();
        return claims.entrySet().stream().filter(e -> referrals.contains(e.getKey()) || differences.containsKey(e.getKey()))
            .flatMap(e -> e.getValue().stream()).anyMatch(c -> contains(text, c.statement()));
    }

    boolean hasUnmappedDocumentDependency(String text) {
        String body = compact(text);
        var keywords = Map.of("income", "소득", "assets", "자산", "vehicle", "자동차|차량", "age", "연령|나이|세이상|세이하",
            "housing", "무주택|주택소유", "marriage", "혼인|미혼|배우자");
        return conflicting().keySet().stream().anyMatch(key -> java.util.regex.Pattern.compile(
            keywords.getOrDefault(key, "기준") + "|위기준|앞의기준|해당조건|자격충족").matcher(body).find());
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
            for (var part : source.context())
                for (String line : part.text().split("\\R")) {
                    var rule = GuidanceRuleGrammar.parse(line.trim());
                    boolean explained = rule != null && (rule.kind().equals("CONDITION") && keys.contains(rule.key())
                        || rule.kind().equals("DOCUMENT") && documentKeys.contains(rule.key()));
                    text.append(explained ? "[conflicting-" + rule.kind() + ":" + rule.key() + "]" : line).append('\n');
                }
            bodies.add(compact(text.toString()));
        }
        return bodies.size() == 1;
    }

    List<ReviewIssue> bind(List<Question> questions) {
        var result = new ArrayList<ReviewIssue>();
        for (var entry : conflicting().entrySet()) {
            var group = entry.getValue();
            var ids = questions.stream().filter(q -> group.stream().anyMatch(c -> contains(q.reason(), c.statement())))
                .map(Question::id).distinct().toList();
            if (ids.isEmpty()) continue;
            String detail = "공식 자료에 서로 다른 기준이 있습니다. 아래 항목은 어느 한쪽을 확정하지 않고 기관 확인이 필요합니다.\n"
                + group.stream().map(Claim::statement).distinct().collect(Collectors.joining("\n"));
            result.add(new ReviewIssue("e-conflict-" + entry.getKey(), "CONDITION", "공식 신청 기준 확인 필요",
                detail, ids, List.of(), group.stream().map(Claim::evidence).distinct().toList()));
        }
        for (String key : referrals) {
            if (conflicting().containsKey(key)) continue;
            var claim = claims.get(key).get(0);
            var ids = questions.stream().filter(q -> contains(q.reason(), claim.statement())).map(Question::id).distinct().toList();
            if (!ids.isEmpty()) result.add(new ReviewIssue("agency-" + key, "CONDITION", "기관 확인 필요",
                claim.statement(), ids, List.of(), List.of(claim.evidence())));
        }
        return List.copyOf(result);
    }

    boolean fullyBound(List<ReviewIssue> issues) {
        var keys = new HashSet<>(conflicting().keySet());
        keys.addAll(referrals);
        return issues.size() == keys.size() && issues.size() <= 30
            && issues.stream().allMatch(i -> i.detail().length() <= 3000 && i.evidence().size() <= 10);
    }

    /**
     * Additional domains are reconciled with explicit scopes, not whole-source string equality.
     */
    static AutomaticGuidanceCompiler.Result reconcileAdditionalSources(Content content, SupplementaryRules.Context context, String checked) {
        var questions = new ArrayList<>(content.questions());
        var review = new ArrayList<>(content.reviewIssues());
        var blockers = new LinkedHashSet<String>();
        var combinations = SupplyInventory.combinations(context.primary().source());
        var options = new ArrayList<AnswerOption>();
        var values = new LinkedHashMap<String, List<String>>();
        int number = 0;
        for (var entry : combinations.entrySet()) {
            var keys = new ArrayList<String>();
            for (String shape : new TreeSet<>(entry.getValue())) {
                String key = "SITE_" + (++number);
                keys.add(key);
                options.add(new AnswerOption(key, entry.getKey() + " · " + shape + "형"));
            }
            values.put(entry.getKey(), List.copyOf(keys));
        }
        var e = new Evidence(context.primary().source().label(), context.primary().source().url(), checked);
        String selector = "application-complex-shape";
        if (!context.sites().isEmpty() || !context.inventories().isEmpty()) {
            if (options.isEmpty())
                return new AutomaticGuidanceCompiler.Result(false, List.of("SUPPLEMENTARY_SUPPLY_SCOPE_UNREAD"), content);
            options.add(new AnswerOption("UNKNOWN", "아직 정하지 않았어요", true));
            questions.add(new Question(selector, "어느 단지·주택형을 신청하나요?", "공고의 신청 유형별 허용 주택형을 먼저 확인해요. 선택한 단지의 시설·비용 안내를 보여줘요. 정하지 않았다면 모든 안내를 남겨요.", e, options));
        }
        for (var site : context.sites()) {
            var grouped = site.clauses().stream().collect(Collectors.groupingBy(c -> c.complex() + (c.topic() == SiteInstructions.Topic.WASHER_LOCATION_UNCLEAR ? ":WASHER" : ":NOTES"), LinkedHashMap::new, Collectors.toList()));
            for (var group : grouped.values()) {
                var first = group.get(0);
                String title = first.complex() + (first.topic() == SiteInstructions.Topic.WASHER_LOCATION_UNCLEAR ? " · 14A/B형 공동세탁실 위치 확인" : " · 시설·비용·계약 전 확인 안내");
                String text = group.stream().map(c -> siteTopic(c.topic()) + (c.trigger().isBlank() ? "" : " · " + c.trigger()) + "\n" + c.text()).collect(Collectors.joining("\n"));
                var evidence = new Evidence(site.source().label() + " · " + first.location(), EvidenceLocationUrl.forSource(site.source(), first.location(), first.text()), checked);
                var ids = references(questions, "site-" + grouped.entrySet().stream().filter(entry -> entry.getValue() == group).findFirst().orElseThrow().getKey(), title, text, evidence,
                    new QuestionApplicability(selector, values.get(first.complex())));
                if (first.topic() == SiteInstructions.Topic.WASHER_LOCATION_UNCLEAR)
                    review.add(new ReviewIssue("site-washer-floor-" + UUID.nameUUIDFromBytes(first.complex().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "CONDITION", "108동 공동세탁실 층 표기 확인",
                        "원문은 ‘108동 홀수층(1층,21층)’으로 적혀 있지만 괄호의 층을 포함하는지 제외하는지 명시하지 않았어요. 14A/B형은 공동세탁실을 이용한다는 안내는 유지하며, 108동의 실제 이용 층만 기관에 확인해요.", ids, List.of(), List.of(evidence)));
            }
            var referenceGroups = site.references().stream().collect(Collectors.groupingBy(r -> r.complex() + ":" + r.title(), LinkedHashMap::new, Collectors.toList()));
            for (var referenceGroup : referenceGroups.values()) {
                var reference = referenceGroup.get(0);
                var evidence = new Evidence(site.source().label() + " · " + reference.location(), EvidenceLocationUrl.forSource(site.source(), reference.location(), reference.title()), checked);
                String text = referenceGroup.stream().map(r -> r.location() + "\n" + r.text()).collect(Collectors.joining("\n"));
                references(questions, "site-reference-" + reference.complex() + reference.title(), (reference.complex() == null ? "" : reference.complex() + " · ") + reference.title(), text, evidence, reference.complex() == null ? null : new QuestionApplicability(selector, values.get(reference.complex())));
            }
        }
        for (var inventory : context.inventories().entrySet()) {
            var source = inventory.getKey();
            var read = inventory.getValue();
            var evidence = new Evidence(source.label(), source.url(), checked);
            var ids = references(questions, "supply-inventory", "신청·계약 가능한 동호 확인", "공고 본문의 단지·주택형별 대상과 공실 목록을 대조했어요. 대학생·청년·주거급여수급자 등 유형에 따라 가능한 동호가 다를 수 있어요. 공실은 계약 진행 중 바뀔 수 있으므로 세부 동호는 계약 안내에서 다시 확인해요.", evidence, null);
            var differences = read.differences().stream().collect(Collectors.groupingBy(SupplyInventory.Difference::field, LinkedHashMap::new, Collectors.toList()));
            for (var field : differences.entrySet()) {
                String detail = field.getValue().stream().map(d -> d.location() + " · 본문: " + d.primary() + " / 공실 목록: " + d.inventory()).distinct().collect(Collectors.joining("\n"));
                if (detail.length() > 3000) {
                    blockers.add("SUPPLY_DIFFERENCE_LIMIT");
                    continue;
                }
                review.add(new ReviewIssue("supply-difference-" + UUID.nameUUIDFromBytes(field.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "CONDITION", field.getKey() + " 확인 필요", detail, ids, List.of(), List.of(e, evidence)));
            }
        }
        for (var listing : context.listings()) {
            var groups = listing.clauses().stream().filter(c -> c.topic() != ListingInstructions.Topic.SCHEDULE).collect(Collectors.groupingBy(ListingInstructions.Clause::topic, LinkedHashMap::new, Collectors.toList()));
            for (var group : groups.values()) {
                var first = group.get(0);
                var evidence = new Evidence(listing.source().label() + " · " + first.location(), listing.source().url(), checked);
                String text = group.stream().map(ListingInstructions.Clause::text).collect(Collectors.joining("\n"));
                references(questions, "listing-" + first.topic(), first.title(), text, evidence, null);
            }
        }
        if (questions.size() > Content.MAX_QUESTIONS) blockers.add("EVIDENCE_GUIDANCE_TOO_MANY_QUESTIONS");
        return new AutomaticGuidanceCompiler.Result(blockers.isEmpty(), List.copyOf(blockers), new Content(content.summary(), content.evidence(), content.attachments(), questions, content.documents(), content.schedules(), review, content.applicationClosure()));
    }

    private static String siteTopic(SiteInstructions.Topic topic) {
        return switch (topic) {
            case COST -> "비용 부담";
            case USE_RESTRICTION, EV_PARKING -> "시설 사용·주차 제한";
            case CLAIM_RESTRICTION -> "원문에 명시한 사유의 이의·변경 제한";
            case COOPERATION -> "공동시설 운영 협조";
            case DELAY_COMPENSATION -> "조건부 입주 지연·보상 제한";
            case SCHOOL -> "통학·학교 배정";
            default -> "시설·현장 정보";
        };
    }

    private static List<String> references(List<Question> questions, String key, String title, String text, Evidence evidence, QuestionApplicability scope) {
        var chunks = new ArrayList<String>();
        var chunk = new StringBuilder();
        for (String line : text.lines().toList()) {
            if (line.length() > 1850) throw new IllegalArgumentException("Source clause exceeds reference limit");
            if (chunk.length() + line.length() + 1 > 1850) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(line).append('\n');
        }
        if (!chunk.isEmpty()) chunks.add(chunk.toString().strip());
        var ids = new ArrayList<String>();
        for (int i = 0; i < chunks.size(); i++) {
            String id = "supplement-" + UUID.nameUUIDFromBytes((key + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ids.add(id);
            questions.add(new Question(id, title + (chunks.size() > 1 ? " · " + (i + 1) + "/" + chunks.size() : ""), chunks.get(i), evidence,
                List.of(new AnswerOption("ACK", "안내를 확인했어요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)), "SINGLE", null, false, List.of(), scope));
        }
        return List.copyOf(ids);
    }

    private static boolean contains(String text, String statement) {
        return compact(text).contains(compact(statement));
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", "");
    }
}
