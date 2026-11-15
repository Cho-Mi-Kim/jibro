package org.jibro.collection.review;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.EvidenceValidation;
import org.jibro.notice.HousingModels.*;

/**
 * Compiles complete official sections into source-confirmation tasks. It does not turn a percentage
 * table, waiver, ranking, or exception into an unqualified eligibility predicate.
 */
public final class EvidenceGuidanceCompiler {
    private EvidenceGuidanceCompiler() {}

    private static final Pattern NUMBERED = Pattern.compile("^\\s*(?:제\\s*)?\\d{1,2}\\s*[.．]\\s*([가-힣].*)$");
    private static final Pattern CONDITION_TITLE = Pattern.compile("신청\\s*자격|입주\\s*자격|선정|순위|배점|주택.*(?:소유|판정)|소득.*기준|자산.*기준|무주택|세대구성|유의사항|공고문\\s*숙지");
    private static final Pattern DOCUMENT_TITLE = Pattern.compile("(?:제출|구비|신청)\\s*서류");
    private static final Pattern OTHER_SECTION_TITLE = Pattern.compile("^(?:모집\\s*일정|신청\\s*방법|임대\\s*조건|공급\\s*(?:대상|일정)|주택\\s*(?:단지|형별|성능)|친환경주택|시행자|편의시설|단지\\s*개요|계약\\s*안내|문의\\s*처|당첨(?:자)?\\s*발표).*");
    private static final Pattern CONTEXT_TITLE = Pattern.compile("^(?:[■▪□◆●]\\s*)?(?:입주자격\\s*완화|신청자격|신청\\s*자격|주택\\s*소유여부|소득\\s*및\\s*자산|공고문\\s*숙지|제출\\s*서류|구비\\s*서류).{0,70}$");
    private static final Pattern PAGE_REFERENCE = Pattern.compile("(?:공고문\\s*)?(\\d{1,3})\\s*(?:쪽|[Pp](?![A-Za-z]))");
    private static final int CHUNK = 1550;
    private static final int MAX_GUIDANCE_QUESTIONS = 20;
    private static final Pattern INDIVIDUAL_VEHICLE = Pattern.compile("세대구성원전원이보유하고있는개별자동차가액\\(?([0-9,]+)\\)?만원이하");
    private static final Pattern RANK = Pattern.compile("(?<![0-9])([1-9])\\s*순위");
    private static final List<String> DOCUMENTS = List.of("주민등록표등본", "주민등록표초본", "가족관계증명서", "기본증명서",
        "혼인관계증명서", "개인정보수집·이용및제3자제공동의서", "금융정보등제공동의서", "입주자격완화입주자확인서",
        "예비입주자중복선정불가사항확인서", "공급신청서", "자산보유사실확인서", "수급자증명서", "한부모가족증명서",
        "장애인증명서", "건강보험자격득실확인서", "재학증명서", "졸업증명서", "외국인등록증", "임대차계약서", "소득금액증명");

    private record Segment(String category, String heading, Source source, String location, String text) {}
    public static AutomaticGuidanceCompiler.Result compile(Draft draft, Content original) {
        return compile(draft, original, false);
    }

    public static AutomaticGuidanceCompiler.Result compileScoped(Draft draft, Content original) {
        return compile(draft, original, true);
    }

    private static AutomaticGuidanceCompiler.Result compile(Draft draft, Content original, boolean scoped) {
        var issues = new LinkedHashSet<String>();
        if (draft == null || original == null || !"READY".equals(draft.status()) || draft.checkedAt() == null
            || draft.sources() == null || draft.sources().isEmpty()) return hold("EVIDENCE_SOURCE_NOT_COMPLETE");
        if (draft.sources().size() > 51) return hold("EVIDENCE_SOURCE_LIMIT");
        if (draft.sources().stream().anyMatch(org.jibro.collection.extraction.OcrProvenance::contains))
            return hold(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED);
        String day = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        try {
            EvidenceValidation.validate(original.evidence());
            if (original.attachments() != null) original.attachments().forEach(EvidenceValidation::validate);
        }
        catch (RuntimeException invalid) { return hold("EVIDENCE_OFFICIAL_SOURCE_REQUIRED"); }
        var sourceUrls = new HashSet<String>();
        for (Source source : draft.sources()) if (source != null) sourceUrls.add(source.url());
        if (!sourceUrls.contains(original.evidence().url()) || original.attachments() != null
                && original.attachments().stream().anyMatch(a -> !sourceUrls.contains(a.url())))
            return hold("SOURCE_COVERAGE_INCOMPLETE");
        List<Segment> segments = new ArrayList<>();
        Map<String, String> simpleClaims = new HashMap<>();
        var conflicts = new ScopedEvidenceConflicts();
        var documentConflicts = new ScopedDocumentConflicts();
        Set<String> governingBodies = new HashSet<>();
        for (var source : draft.sources()) {
            if (!trusted(source)) { issues.add("EVIDENCE_SOURCE_NOT_COMPLETE"); continue; }
            if (source.context().size() > 200 || source.context().stream().filter(Objects::nonNull).mapToLong(p -> p.text() == null ? 0 : p.text().length()).sum() > 1_000_000) {
                issues.add("EVIDENCE_SOURCE_LIMIT"); continue;
            }
            for (Part part : source.context()) {
                if (part == null || part.text() == null || part.location() == null || part.location().isBlank()
                    || part.text().replaceAll("[\\s\\p{Zs}]+", "").isEmpty() || part.text().contains("[발췌 길이 제한]"))
                    issues.add("EVIDENCE_CONTEXT_INCOMPLETE");
            }
            if (!issues.isEmpty()) continue;
            String wholeSource = source.context().stream().map(Part::text).reduce("", (a, b) -> a + "\n" + b);
            governingBodies.add(compact(wholeSource));
            if (isInstitutionalNotice(source)) issues.add("EVIDENCE_INSTITUTIONAL_SCOPE_UNSUPPORTED");
            var selected = sections(source);
            segments.addAll(selected);
            for (var segment : selected) {
                // Explicit same-subject claims with different values are a conflict, never averaged.
                for (String line : segment.text().split("\\n")) {
                    var rule = GuidanceRuleGrammar.parse(line.trim());
                    if (rule != null && rule.kind().equals("DOCUMENT"))
                        documentConflicts.add(rule, line.trim(), evidence(segment, day));
                    if (rule != null && rule.kind().equals("CONDITION")) {
                        conflicts.add(rule, line.trim(), evidence(segment, day));
                        var old = simpleClaims.putIfAbsent(rule.key(), rule.value());
                        if (!scoped && old != null && !old.equals(rule.value())) issues.add("EVIDENCE_CONFLICTING_RULE");
                    }
                }
            }
            validateReferences(source, selected, issues);
        }
        if (!scoped && documentConflicts.detected()) issues.add("EVIDENCE_CONFLICTING_RULE");
        // Different governing texts require independent conflict reconciliation. Unknown prose is
        // not proof of agreement just because the small exact grammar finds no contradiction.
        if (governingBodies.size() > 1 && !(scoped && issues.isEmpty()
            && conflicts.explainsEverySourceDifference(draft.sources(), documentConflicts.conflictingKeys())))
            issues.add("EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW");
        if (segments.stream().noneMatch(s -> s.category().equals("CONDITION"))) issues.add("EVIDENCE_QUALIFICATION_SECTION_MISSING");
        var docSegments = segments.stream().filter(s -> s.category().equals("DOCUMENT")).toList();
        if (docSegments.isEmpty()) issues.add("EVIDENCE_DOCUMENT_SECTION_MISSING");
        if (scoped && docSegments.stream().anyMatch(s -> conflicts.hasUnmappedDocumentDependency(s.text())))
            issues.add("EVIDENCE_CONFLICT_DOCUMENT_DEPENDENCY_UNMAPPED");
        if (!issues.isEmpty()) return new AutomaticGuidanceCompiler.Result(false, List.copyOf(issues), null);

        var questions = new ArrayList<Question>();
        var documents = new ArrayList<DocumentTemplate>();
        var attachmentMap = new LinkedHashMap<String, Evidence>();
        if (original.attachments() != null) original.attachments().forEach(a -> attachmentMap.put(a.url(), a));
        for (Source source : draft.sources()) {
            if (!source.url().equals(original.evidence().url()))
                attachmentMap.putIfAbsent(source.url(), new Evidence(source.label(), source.url(), day));
        }
        var seenSegments = new HashSet<String>();
        for (Segment segment : segments) {
            if (!seenSegments.add(segment.category() + compact(segment.text()))) continue;
            var evidence = evidence(segment, day);
            if (!valid(evidence)) { issues.add("EVIDENCE_LOCATION_INVALID"); continue; }
            boolean headingContinues = segments.stream().filter(s -> s.source().url().equals(segment.source().url())
                    && s.heading().equals(segment.heading())).count() > 1;
            if (!scoped || !conflicts.affects(segment.text()))
                addQualifiedQuestions(segment, evidence, questions, headingContinues);
            List<String> chunks = scoped ? conflicts.partition(segment.text()).stream().flatMap(text -> chunks(text).stream()).toList()
                : chunks(segment.text());
            for (int i = 0; i < chunks.size(); i++) {
                String suffix = chunks.size() > 1 ? " · " + (i + 1) + "/" + chunks.size() : "";
                String title = segment.category().equals("DOCUMENT") ? "서류 제출 대상·예외 확인" : "신청 기준·순위·예외 확인";
                String reason = "원문 구간 " + segment.location() + suffix + "입니다. 연결된 앞뒤 구간과 예외를 함께 확인하세요.\n\n" + chunks.get(i);
                questions.add(new Question(id("section", segment.source().url() + segment.location() + segment.category() + segment.heading() + segment.text() + i),
                    title + " · " + segment.location() + suffix, reason, evidence, confirmationOptions()));
            }
        }
        var foundNames = new LinkedHashMap<String, Segment>();
        for (Segment segment : docSegments) {
            String text = compact(segment.text());
            for (String name : DOCUMENTS) if (text.contains(compact(name))) foundNames.putIfAbsent(name, segment);
        }
        if (foundNames.isEmpty()) issues.add("EVIDENCE_DOCUMENT_NAMES_MISSING");
        else {
            // Every applicability choice is explicit. Merely mentioning a document never makes it mandatory.
            for (var found : foundNames.entrySet()) addDocument(found.getKey(), found.getValue(), day, questions, documents, false);
            addDocument("그 밖의 공고별 제출서류", docSegments.get(0), day, questions, documents, true);
        }
        if (questions.size() > MAX_GUIDANCE_QUESTIONS) issues.add("EVIDENCE_GUIDANCE_TOO_MANY_QUESTIONS");
        if (documents.size() > 100 || attachmentMap.size() > 50) issues.add("EVIDENCE_GUIDANCE_LIMIT");
        if (!issues.isEmpty()) return new AutomaticGuidanceCompiler.Result(false, List.copyOf(issues), null);
        var reviewIssues = new ArrayList<ReviewIssue>(scoped ? conflicts.bind(questions) : List.of());
        if (scoped && !conflicts.fullyBound(reviewIssues)) return hold("EVIDENCE_CONFLICT_SCOPE_UNMAPPED");
        if (scoped) {
            var scopedDocuments = documentConflicts.bind(questions, documents);
            if (!scopedDocuments.blockers().isEmpty()) return new AutomaticGuidanceCompiler.Result(false, scopedDocuments.blockers(), null);
            reviewIssues.addAll(scopedDocuments.reviewIssues());
        }
        return new AutomaticGuidanceCompiler.Result(true, List.of(), new Content(original.summary(), original.evidence(), List.copyOf(attachmentMap.values()),
            List.copyOf(questions), List.copyOf(documents), original.schedules(), reviewIssues));
    }

    private static void addQualifiedQuestions(Segment segment, Evidence evidence, List<Question> questions, boolean headingContinues) {
        // A semantic question is emitted only with its complete local scope, waiver and table. A
        // longer or continued table remains a source check, never a threshold guessed from cells.
        if (!segment.category().equals("CONDITION") || segment.text().length() > CHUNK) return;
        String text = compact(segment.text());
        var vehicle = INDIVIDUAL_VEHICLE.matcher(text);
        if (text.contains("소득요건배제") && text.contains("총자산요건배제") && text.contains("완화요건미적용") && vehicle.find()) {
            String amount = vehicle.group(1);
            questions.add(new Question(id("rule", segment.source().url() + "waiver-individual-vehicle"),
                "공고의 제외·예외를 적용한 뒤 세대구성원 전원의 자동차별 가액이 " + amount + "만원 이하인가요?",
                "이번 공고는 소득·총자산 요건을 배제하지만 자동차는 완화하지 않습니다. 자동차별 기준을 합산 가액으로 바꾸지 마세요. 연결된 원문의 제외·예외도 함께 적용해 주세요.\n\n" + segment.text(),
                evidence, List.of(new AnswerOption("YES", "기준에 해당해요"), new AnswerOption("NO", "추가 확인이 필요해요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
        }
        if (headingContinues || !(segment.heading().contains("선정") || segment.heading().contains("순위"))) return;
        var rankValues = new TreeSet<String>();
        var ranks = RANK.matcher(segment.text());
        while (ranks.find()) rankValues.add(ranks.group(1));
        if (rankValues.size() < 2) return;
        var options = new ArrayList<AnswerOption>();
        for (String rank : rankValues) options.add(new AnswerOption("RANK_" + rank, rank + "순위"));
        options.add(new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
        questions.add(new Question(id("rule", segment.source().url() + segment.location() + segment.text() + "rank"),
            "원문의 가구 범위와 예외를 적용하면 어느 신청 순위에 해당하나요?",
            "순위는 신청 자격과 별개입니다. 아래 순위별 대상·가구 범위·기준과 동점 처리 방법을 함께 확인해 선택하세요. 기준이 다음 쪽에 이어지면 원문 전체를 확인하세요.\n\n" + segment.text(), evidence, options));
    }

    private static boolean isInstitutionalNotice(Source source) {
        String title = compact(source.label() + " " + source.context().get(0).text().substring(0, Math.min(400, source.context().get(0).text().length())));
        return title.contains("어린이집") || title.contains("기관공급") || title.contains("운영예정자") || title.contains("기업체모집");
    }

    private static void addDocument(String name, Segment segment, String day, List<Question> questions, List<DocumentTemplate> documents, boolean additional) {
        String qid = id("applies", name);
        var evidence = evidence(segment, day);
        String reason = additional ? "앞의 서류 구간 전체에서 아직 목록에 없는 서류나 추가 증빙의 제출 대상인지 확인해 주세요. 대상이 불명확하면 ‘잘 모르겠어요’를 선택하세요."
            : "앞의 서류 구간 전체에서 ‘" + name + "’의 제출 대상·생략 조건·추가 발급 대상과 발급일 기준을 함께 확인해 주세요. 이름이 보인다는 이유만으로 필수 서류로 처리하지 않습니다.";
        questions.add(new Question(qid, name + " 제출 대상에 해당하나요?", reason, evidence,
            List.of(new AnswerOption("YES", "제출 대상이에요"), new AnswerOption("NO", "제출 대상이 아니에요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
        documents.add(new DocumentTemplate(id("document", name), name, qid,
            "공식 원문의 제출 대상·예외·발급 기준을 함께 확인하세요. 대상 확인 답변에 따라 준비 목록에 반영합니다.", "공고문 발급처 확인", null, evidence, List.of("YES")));
    }

    private static List<Segment> sections(Source source) {
        var result = new ArrayList<Segment>();
        String category = null, heading = "";
        for (Part part : source.context()) {
            var text = new StringBuilder();
            String partCategory = category, partHeading = heading;
            for (String raw : part.text().replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.matches("-\\s*\\d+\\s*-")) continue;
                var numbered = NUMBERED.matcher(line);
                boolean explicit = numbered.matches();
                String title = explicit ? numbered.group(1) : line;
                if (explicit || CONTEXT_TITLE.matcher(line).matches()) {
                    String next = DOCUMENT_TITLE.matcher(title).find() ? "DOCUMENT" : CONDITION_TITLE.matcher(title).find() ? "CONDITION" : null;
                    // A numbered exception/sub-clause is not a new unrelated chapter. Preserve it.
                    if (next != null || explicit && OTHER_SECTION_TITLE.matcher(title).matches()) {
                        flush(result, source, part.location(), partCategory, partHeading, text);
                        category = next; heading = title; partCategory = category; partHeading = heading;
                    }
                }
                if (partCategory != null) text.append(line).append('\n');
            }
            flush(result, source, part.location(), partCategory, partHeading, text);
        }
        return result;
    }

    private static void flush(List<Segment> result, Source source, String location, String category, String heading, StringBuilder text) {
        if (category != null && !text.isEmpty()) result.add(new Segment(category, heading, source, location, text.toString().strip()));
        text.setLength(0);
    }

    private static void validateReferences(Source source, List<Segment> selected, Set<String> issues) {
        var pages = new HashSet<Integer>();
        for (Part part : source.context()) {
            var matcher = Pattern.compile("(?:공고문\\s*)?(\\d+)쪽").matcher(part.location());
            while (matcher.find()) pages.add(Integer.parseInt(matcher.group(1)));
        }
        for (var segment : selected) {
            var matcher = PAGE_REFERENCE.matcher(segment.text());
            while (matcher.find()) {
                int page = Integer.parseInt(matcher.group(1));
                if (!pages.isEmpty() && !pages.contains(page)) issues.add("EVIDENCE_REFERENCED_PAGE_MISSING");
            }
        }
    }

    private static List<String> chunks(String text) {
        var chunks = new ArrayList<String>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + CHUNK);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > offset + CHUNK / 2) end = newline + 1;
            }
            chunks.add(text.substring(offset, end));
            offset = end;
        }
        return chunks;
    }

    private static List<AnswerOption> confirmationOptions() {
        return List.of(new AnswerOption("YES", "기준과 예외를 함께 확인했어요"), new AnswerOption("NO", "추가 확인이 필요해요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
    }
    private static Evidence evidence(Segment s, String day) { return new Evidence(s.source().label() + " · " + s.location(), EvidenceLocationUrl.forSource(s.source(), s.location(), s.text()), day); }
    private static boolean valid(Evidence evidence) { try { EvidenceValidation.validate(evidence); return true; } catch (RuntimeException invalid) { return false; } }
    private static String compact(String text) { return text.replaceAll("[\\s\\p{Zs}]+", ""); }
    private static String id(String type, String value) { return "e-" + type + "-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static boolean trusted(Source source) {
        try {
            URI url = URI.create(source.url());
            return source.status().equals("EXTRACTED") && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
                && !source.context().isEmpty() && "https".equals(url.getScheme()) && Set.of("apply.lh.or.kr", "www.lh.or.kr").contains(url.getHost())
                && url.getRawUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
        } catch (RuntimeException invalid) { return false; }
    }
    private static AutomaticGuidanceCompiler.Result hold(String issue) { return new AutomaticGuidanceCompiler.Result(false, List.of(issue), null); }
}
