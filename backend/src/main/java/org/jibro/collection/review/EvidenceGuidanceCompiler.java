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
    private EvidenceGuidanceCompiler() {
    }

    private static final Pattern NUMBERED = Pattern.compile("^\\s*(?:제\\s*)?\\d{1,2}\\s*[.．]\\s*([가-힣].*)$");
    private static final Pattern CONDITION_TITLE = Pattern.compile("신청\\s*자격|입주\\s*자격|선정|순위|배점|주택.*(?:소유|판정)|소득.*기준|자산.*기준|무주택|세대구성|유의사항|공고문\\s*숙지");
    private static final Pattern DOCUMENT_TITLE = Pattern.compile("(?:제출|구비|신청)\\s*서류");
    private static final Pattern OTHER_SECTION_TITLE = Pattern.compile("^(?:모집\\s*일정|신청\\s*방법|임대\\s*조건|공급\\s*(?:대상|일정)|주택\\s*(?:단지|형별|성능)|친환경주택|시행자|편의시설|단지\\s*개요|계약\\s*안내|문의\\s*처|당첨(?:자)?\\s*발표).*");
    private static final Pattern CONTEXT_TITLE = Pattern.compile("^(?:[■▪□◆●]\\s*)?(?:입주\\s*자격(?:\\s*완화)?|신청자격|신청\\s*자격|주택\\s*소유여부|소득\\s*및\\s*자산|공고문\\s*숙지|기타\\s*유의사항|제출\\s*서류|구비\\s*서류).{0,70}$");
    private static final Pattern PAGE_REFERENCE = Pattern.compile("(?:공고문\\s*)?(\\d{1,3})\\s*(?:쪽|[Pp](?![A-Za-z]))");
    private static final int CHUNK = 1550;
    private static final Pattern INDIVIDUAL_VEHICLE = Pattern.compile("세대구성원전원이보유하고있는개별자동차가액\\(?([0-9,]+)\\)?만원이하");
    private static final Pattern RANK = Pattern.compile("(?<![0-9])([1-9])\\s*순위");
    private static final List<String> DOCUMENTS = List.of("주민등록표등본", "주민등록표초본", "가족관계증명서", "기본증명서",
            "혼인관계증명서", "개인정보수집·이용및제3자제공동의서", "금융정보등제공동의서", "입주자격완화입주자확인서",
            "예비입주자중복선정불가사항확인서", "공급신청서", "자산보유사실확인서", "수급자증명서", "한부모가족증명서",
            "장애인증명서", "건강보험자격득실확인서", "재학증명서", "졸업증명서", "외국인등록증", "임대차계약서", "소득금액증명");

    private record Segment(String category, String heading, Source source, String location, String text,
                           Evidence replacement) {
        Segment(String category, String heading, Source source, String location, String text) {
            this(category, heading, source, location, text, null);
        }
    }

    public static AutomaticGuidanceCompiler.Result compile(Draft draft, Content original) {
        return compile(draft, original, Set.of(), List.of());
    }

    static AutomaticGuidanceCompiler.Result compile(Draft draft, Content original, Set<String> superseded,
                                                    List<AutomaticGuidanceCompiler.LocatedRule> replacements) {
        var issues = new LinkedHashSet<String>();
        var replaced = new LinkedHashSet<String>();
        var detailedDocuments = new ArrayList<AutomaticGuidanceCompiler.LocatedRule>();
        var applicantFields = new ArrayList<AutomaticGuidanceCompiler.LocatedRule>();
        var representedSections = new HashSet<Segment>();
        var applicantGroups = new LinkedHashMap<String, List<String>>();
        if (draft == null || original == null || !"READY".equals(draft.status()) || draft.checkedAt() == null
                || draft.sources() == null || draft.sources().isEmpty()) return hold("EVIDENCE_SOURCE_NOT_COMPLETE");
        if (draft.sources().size() > 51) return hold("EVIDENCE_SOURCE_LIMIT");
        if (draft.sources().stream().anyMatch(org.jibro.collection.extraction.OcrProvenance::contains))
            return hold(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED);
        String day = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        try {
            EvidenceValidation.validate(original.evidence());
            if (original.attachments() != null) original.attachments().forEach(EvidenceValidation::validate);
        } catch (RuntimeException invalid) {
            return hold("EVIDENCE_OFFICIAL_SOURCE_REQUIRED");
        }
        var sourceUrls = new HashSet<String>();
        for (Source source : draft.sources())
            if (source != null) sourceUrls.add(EvidenceLocationUrl.base(source.url()));
        if (!sourceUrls.contains(EvidenceLocationUrl.base(original.evidence().url())) || original.attachments() != null
                && original.attachments().stream().anyMatch(a -> !sourceUrls.contains(EvidenceLocationUrl.base(a.url()))))
            return hold("SOURCE_COVERAGE_INCOMPLETE");
        for (var source : draft.sources()) {
            if (!trusted(source)) return hold("EVIDENCE_SOURCE_NOT_COMPLETE");
            if (source.context().size() > 200 || source.context().stream().mapToLong(p -> p.text() == null ? 0 : p.text().length()).sum() > 1_000_000)
                return hold("EVIDENCE_SOURCE_LIMIT");
            if (source.context().stream().anyMatch(p -> p == null || p.text() == null || p.location() == null || p.location().isBlank() || compact(p.text()).isEmpty() || p.text().contains("[발췌 길이 제한]")))
                return hold("EVIDENCE_CONTEXT_INCOMPLETE");
        }
        var supplementary = SupplementaryRules.context(draft);
        issues.addAll(supplementary.blockers());
        List<Segment> segments = new ArrayList<>();
        var conflicts = new ScopedEvidenceConflicts();
        var documentConflicts = new ScopedDocumentConflicts();
        Set<String> governingBodies = new HashSet<>();
        for (var raw : draft.sources()) {
            // A parsed supplemental role is provisional. Its claims must still be reconciled and
            // bound below before this compiler can return ready.
            if (supplementary.supplementalUrls().contains(raw.url())) continue;
            var source = OfficialGuidanceRows.read(org.jibro.collection.review.local.DocumentContextReader.normative(raw));
            String wholeSource = source.context().stream().map(Part::text).reduce("", (a, b) -> a + "\n" + b);
            governingBodies.add(compact(wholeSource));
            if (isInstitutionalNotice(source)) issues.add("EVIDENCE_INSTITUTIONAL_SCOPE_UNSUPPORTED");
            var explained = new ArrayList<Part>();
            for (var part : source.context()) {
                var explanation = org.jibro.collection.review.local.GuidanceTableProjection.explanation(source, part,
                        headings -> OfficialGuidanceRows.header(String.join(" : ", headings)));
                if (!explanation.complete()) issues.add("EVIDENCE_TABLE_STRUCTURE_UNVERIFIED");
                explained.add(new Part(part.location(), explanation.text()));
            }
            var selected = sections(source, explained);
            if (!replacements.isEmpty()) {
                var affectedHeadings = selected.stream().filter(segment -> segment.text().lines()
                        .map(FollowupClaimReader::clean).anyMatch(superseded::contains)).map(Segment::heading).toList();
                if (selected.stream().filter(segment -> affectedHeadings.contains(segment.heading()))
                        .anyMatch(segment -> segment.text().lines().map(FollowupClaimReader::clean).anyMatch(line ->
                                !line.equals(FollowupClaimReader.clean(segment.heading())) && !OfficialGuidanceRows.header(line)
                                        && GuidanceRuleGrammar.parse(line) == null)))
                    issues.add("FOLLOWUP_QUALIFICATION_GRAMMAR_UNSUPPORTED");
                selected = amend(selected, superseded, replacements, replaced);
            }
            segments.addAll(selected);
            for (var segment : selected) {
                if (segment.category().equals("CONDITION")) {
                    var numbered = OfficialGuidanceItem.numberedApplicantFields(segment.text());
                    if (numbered.complete()) representedSections.add(segment);
                    if (numbered.group() != null && numbered.members().size() > 1) {
                        var previous = applicantGroups.putIfAbsent(numbered.group().replaceAll("\\s+", ""), numbered.members());
                        if (previous != null && !new HashSet<>(previous).equals(new HashSet<>(numbered.members())))
                            issues.add("EVIDENCE_APPLICANT_GROUP_CONFLICT");
                    }
                    var statements = new ArrayList<>(numbered.statements());
                    statements.addAll(OfficialGuidanceItem.standaloneApplicantFields(segment.heading(), segment.text()));
                    for (String statement : statements) {
                        var rule = GuidanceRuleGrammar.parse(statement);
                        conflicts.add(rule, statement, evidence(segment, day));
                        applicantFields.add(new AutomaticGuidanceCompiler.LocatedRule(rule, evidence(segment, day), statement));
                    }
                }
                // Explicit same-subject claims with different values are a conflict, never averaged.
                for (String line : segment.text().split("\\n")) {
                    var rule = GuidanceRuleGrammar.parse(line.trim());
                    if (rule != null && rule.kind().equals("DOCUMENT_ITEM") && segment.category().equals("DOCUMENT"))
                        detailedDocuments.add(new AutomaticGuidanceCompiler.LocatedRule(rule, evidence(segment, day), line.strip()));
                    if (rule != null && rule.kind().equals("DOCUMENT"))
                        documentConflicts.add(rule, line.trim(), evidence(segment, day));
                    if (rule != null && rule.kind().equals("CONDITION")) {
                        conflicts.add(rule, line.trim(), evidence(segment, day));
                        if (segment.category().equals("CONDITION") && !OfficialGuidanceItem.applicantTypes(rule.title()).isEmpty())
                            applicantFields.add(new AutomaticGuidanceCompiler.LocatedRule(rule, evidence(segment, day), line.strip()));
                    }
                }
            }
            validateReferences(source, selected, issues);
        }
        if (!replaced.containsAll(superseded)) issues.add("FOLLOWUP_QUALIFICATION_SCOPE_UNVERIFIED");
        // Different governing texts require independent conflict reconciliation. Unknown prose is
        // not proof of agreement just because the small exact grammar finds no contradiction.
        if (governingBodies.size() > 1 && !(issues.isEmpty()
                && (conflicts.explainsEverySourceDifference(draft.sources(), documentConflicts.conflictingKeys())
                || draft.sources().stream().allMatch(s -> OfficialGuidanceRows.sameItems(draft.sources().get(0), s, false))
                && superseded.isEmpty() && segmentsCoverFields(draft.sources(), segments))))
            issues.add("EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW");
        if (segments.stream().noneMatch(s -> s.category().equals("CONDITION")))
            issues.add("EVIDENCE_QUALIFICATION_SECTION_MISSING");
        var docSegments = segments.stream().filter(s -> s.category().equals("DOCUMENT")).toList();
        if (docSegments.isEmpty()) issues.add("EVIDENCE_DOCUMENT_SECTION_MISSING");
        if (docSegments.stream().anyMatch(s -> conflicts.hasUnmappedDocumentDependency(s.text())))
            issues.add("EVIDENCE_CONFLICT_DOCUMENT_DEPENDENCY_UNMAPPED");
        if (!issues.isEmpty()) return new AutomaticGuidanceCompiler.Result(false, List.copyOf(issues), null);

        // A selector must include every explicitly advertised category, not merely the clauses
        // supported by the numbered grammar. Keep the original input/reference sections otherwise.
        var missingApplicantTypes = OfficialGuidanceItem.missingApplicantTypes(
                segments.stream().filter(s -> s.category().equals("CONDITION")).map(Segment::text).toList(),
                applicantFields.stream().map(field -> field.rule().title()).toList());
        if (!missingApplicantTypes.isEmpty()) {
            applicantFields.clear();
            representedSections.clear();
        }
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
            if (!valid(evidence)) {
                issues.add("EVIDENCE_LOCATION_INVALID");
                continue;
            }
            boolean headingContinues = segments.stream().filter(s -> s.source().url().equals(segment.source().url())
                    && s.heading().equals(segment.heading())).count() > 1;
            if (!conflicts.affects(segment.text()))
                addQualifiedQuestions(segment, evidence, questions, headingContinues);
            List<String> chunks = conflicts.partition(segment.text()).stream().flatMap(text -> chunks(text).stream()).toList();
            for (int i = 0; i < chunks.size(); i++) {
                String suffix = chunks.size() > 1 ? " · " + (i + 1) + "/" + chunks.size() : "";
                String title = segment.category().equals("DOCUMENT") ? "서류 제출 대상·예외 확인" : "신청 기준·순위·예외 확인";
                String reason = "원문 구간 " + segment.location() + suffix + "입니다. 연결된 앞뒤 구간과 예외를 함께 확인하세요.\n\n" + chunks.get(i);
                questions.add(new Question(id("section", segment.source().url() + segment.location() + segment.category() + segment.heading() + segment.text() + i),
                        title + " · " + segment.location() + suffix, reason, evidence,
                        representedSections.contains(segment) && !conflicts.affects(segment.text())
                                ? List.of(new AnswerOption("ACK", "안내를 확인했어요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))
                                : confirmationOptions()));
            }
        }
        var detailItems = ScopedRuleGuidance.compile(detailedDocuments, original, List.of());
        issues.addAll(detailItems.issues());
        if (detailItems.ready()) {
            questions.addAll(detailItems.content().questions());
            documents.addAll(detailItems.content().documents());
        }
        var foundNames = new LinkedHashMap<String, Segment>();
        for (Segment segment : docSegments)
            for (String name : unrepresentedDocuments(segment, detailItems.ready() ? detailedDocuments : List.of()))
                foundNames.putIfAbsent(name, segment);
        // Complete typed fields can coexist with longer prose. Keep that prose as reference,
        // but send its explicit applicant criteria through the same rule compiler as short notices.
        // Conflicting fields stay in the existing scoped-evidence path, never become a selector.
        boolean applicantFieldsAgree = applicantFields.stream().allMatch(field -> applicantFields.stream()
                .filter(other -> other.rule().key().equals(field.rule().key()))
                .allMatch(other -> other.rule().value().equals(field.rule().value())));
        var applicantItems = ScopedRuleGuidance.compile(applicantFieldsAgree ? applicantFields : List.of(), original, List.of());
        issues.addAll(applicantItems.issues());
        if (applicantItems.ready()) questions.addAll(applicantItems.content().questions());
        if (foundNames.isEmpty() && documents.isEmpty()) issues.add("EVIDENCE_DOCUMENT_NAMES_MISSING");
        else {
            // Every applicability choice is explicit. Merely mentioning a document never makes it mandatory.
            for (var found : foundNames.entrySet())
                addDocument(found.getKey(), found.getValue(), day, questions, documents, false);
            addDocument("그 밖의 공고별 제출서류", docSegments.get(0), day, questions, documents, true);
        }
        if (questions.size() > Content.MAX_QUESTIONS) issues.add("EVIDENCE_GUIDANCE_TOO_MANY_QUESTIONS");
        if (documents.size() > 100 || attachmentMap.size() > 50) issues.add("EVIDENCE_GUIDANCE_LIMIT");
        if (!issues.isEmpty()) return new AutomaticGuidanceCompiler.Result(false, List.copyOf(issues), null);
        var reviewIssues = new ArrayList<ReviewIssue>(conflicts.bind(questions));
        if (!conflicts.fullyBound(reviewIssues)) return hold("EVIDENCE_CONFLICT_SCOPE_UNMAPPED");
        if (detailItems.ready()) reviewIssues.addAll(detailItems.content().reviewIssues());
        if (applicantItems.ready()) reviewIssues.addAll(applicantItems.content().reviewIssues());
        var scopedDocuments = documentConflicts.bind(questions, documents);
        if (!scopedDocuments.blockers().isEmpty())
            return new AutomaticGuidanceCompiler.Result(false, scopedDocuments.blockers(), null);
        reviewIssues.addAll(scopedDocuments.reviewIssues());
        Content result = new Content(original.summary(), original.evidence(), List.copyOf(attachmentMap.values()),
                List.copyOf(questions), List.copyOf(documents), original.schedules(), reviewIssues);
        if (supplementary.active()) {
            var forms = OfficialFormConsistency.supplementaryInstructions(result, supplementary, day);
            if (!forms.ready()) return forms;
            var additional = ScopedEvidenceConflicts.reconcileAdditionalSources(forms.content(), supplementary, day);
            if (!additional.ready()) return additional;
            result = additional.content();
        }
        return new AutomaticGuidanceCompiler.Result(true, List.of(), result, Map.copyOf(applicantGroups));
    }

    private static boolean segmentsCoverFields(List<Source> sources, List<Segment> segments) {
        for (Source source : sources) {
            var fields = new HashSet<String>();
            for (var segment : segments) {
                if (!source.url().equals(segment.source().url())) continue;
                segment.text().lines().map(String::strip).filter(line -> {
                    var rule = OfficialGuidanceItem.parse(line);
                    return rule != null && segment.category().equals(rule.kind().equals("CONDITION") ? "CONDITION" : "DOCUMENT");
                }).forEach(fields::add);
            }
            if (source.context().stream().flatMap(p -> p.text().lines()).map(String::strip)
                    .anyMatch(line -> OfficialGuidanceItem.parse(line) != null && !fields.contains(line))) return false;
        }
        return true;
    }

    private static List<Segment> amend(List<Segment> segments, Set<String> superseded,
                                       List<AutomaticGuidanceCompiler.LocatedRule> replacements, Set<String> replaced) {
        var result = new ArrayList<Segment>();
        for (Segment segment : segments) {
            var kept = new StringBuilder();
            for (String line : segment.text().lines().toList()) {
                String original = FollowupClaimReader.clean(line);
                var rule = superseded.contains(original) ? GuidanceRuleGrammar.parse(original) : null;
                boolean correctSection = rule != null && segment.category().equals(rule.kind().equals("CONDITION") ? "CONDITION" : "DOCUMENT");
                var matches = !correctSection ? List.<AutomaticGuidanceCompiler.LocatedRule>of() : replacements.stream()
                        .filter(r -> r.rule().kind().equals(rule.kind()) && r.rule().key().equals(rule.key())).toList();
                if (matches.isEmpty()) {
                    kept.append(line).append('\n');
                    continue;
                }
                flush(result, segment.source(), segment.location(), segment.category(), segment.heading(), kept);
                replaced.add(original);
                for (var match : matches)
                    result.add(new Segment(segment.category(), segment.heading(), segment.source(), match.evidence().label(),
                            match.statement(), match.evidence()));
            }
            flush(result, segment.source(), segment.location(), segment.category(), segment.heading(), kept);
        }
        return result;
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

    private static Set<String> unrepresentedDocuments(Segment segment, List<AutomaticGuidanceCompiler.LocatedRule> represented) {
        var names = new LinkedHashSet<String>();
        var local = represented.stream().filter(item -> EvidenceLocationUrl.base(item.evidence().url()).equals(segment.source().url())).toList();
        String scan = segment.text();
        // This is a formatting instruction for already-described proofs, not another recipient.
        // Keep the whole instruction in the source explanation; omit only its noun-only inputs.
        var format = Pattern.compile("(?m)^[▪•※]?\\s*((?:[가-힣]+[,，]\\s*)+[가-힣]+)\\s*등\\s*발급\\s*시\\s*주민등록번호\\s*\\d+자리\\s*숫자\\s*(?:전부|모두)\\s*(?:표기|기재)되도록\\s*발급받아\\s*제출(?:하여야\\s*합니다\\.)?(?:\\s*\\(주민등록번호\\s*표시\\s*예시\\s*:[0-9\\s-]+\\))?[\\t ]*(?=$|[\\r\\n])").matcher(scan);
        var formatted = new StringBuilder();
        while (format.find()) {
            var listed = Arrays.stream(format.group(1).split("[,，]\\s*")).map(String::strip).toList();
            boolean covered = listed.stream().allMatch(name -> DOCUMENTS.contains(name)
                    && local.stream().anyMatch(item -> documentNames(item.rule().title()).contains(name)));
            format.appendReplacement(formatted, java.util.regex.Matcher.quoteReplacement(covered ? "" : format.group()));
        }
        format.appendTail(formatted);
        for (String line : formatted.toString().lines().toList()) {
            var rule = GuidanceRuleGrammar.parse(line.strip());
            if (rule != null && rule.kind().equals("DOCUMENT_ITEM") && local.stream().anyMatch(item -> item.statement().equals(line.strip()))) {
                // The main row and its whole method have one document owner. Other named supporting
                // proofs remain candidates; a similarly named obligation elsewhere is never erased.
                if (rule.title().startsWith("서류 안내 · ") && rule.title().endsWith(" · 구비서류")) continue;
                var additional = documentNames(rule.value());
                additional.removeAll(documentNames(rule.title()));
                String recipient = compact(rule.value()).replace('･', '·').replace('∙', '·').replace('ㆍ', '·');
                // “등본에서 확인” / “등본에 등재” describes the proof used to decide the row's
                // recipient. It does not itself ask for another copy of that proof.
                additional.removeIf(name -> !recipient.replaceAll(Pattern.quote(name)
                        + "(?:에서배우자를확인할수없는경우|에등재된경우제출생략)", "").contains(name));
                names.addAll(additional);
            } else {
                // Only the complete issuance-method instruction refers back to an already-owned
                // proof. An “ex.” inside another person's additional submission duty is not a waiver.
                var issuance = Pattern.compile("^[•▪]?일부서류\\(ex\\.([가-힣]+)\\)는공공마이데이터연계를위하여추가정보기입\\(입력\\)이필요하며,추가정보를기입\\(입력\\)하지않거나,잘못된정보를기입\\(입력\\)시연계가불가능하여직접서류를제출하여야합니다\\.$")
                        .matcher(compact(line));
                boolean methodOnly = issuance.matches() && DOCUMENTS.contains(issuance.group(1))
                        && local.stream().anyMatch(item -> documentNames(item.rule().title()).contains(issuance.group(1)));
                if (!methodOnly) names.addAll(documentNames(line));
            }
        }
        return names;
    }

    private static Set<String> documentNames(String text) {
        String normalized = compact(text).replace('･', '·').replace('∙', '·').replace('ㆍ', '·');
        var names = new LinkedHashSet<String>();
        for (String name : DOCUMENTS) if (normalized.contains(name)) names.add(name);
        return names;
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

    private static List<Segment> sections(Source source, List<Part> explained) {
        var result = new ArrayList<Segment>();
        String category = null, heading = "";
        for (Part part : explained) {
            var text = new StringBuilder();
            String partCategory = category, partHeading = heading;
            for (String raw : part.text().replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.matches("-\\s*\\d+\\s*-")) continue;
                var numbered = NUMBERED.matcher(line);
                String applicant = OfficialGuidanceItem.applicantSection(line);
                if (applicant != null) {
                    flush(result, source, part.location(), partCategory, partHeading, text);
                    category = "CONDITION";
                    heading = applicant;
                    partCategory = category;
                    partHeading = heading;
                }
                boolean explicit = numbered.matches();
                String title = explicit ? numbered.group(1) : line;
                if (explicit || CONTEXT_TITLE.matcher(line).matches()) {
                    String normalizedTitle = compact(title);
                    String next = DOCUMENT_TITLE.matcher(normalizedTitle).find() ? "DOCUMENT" : CONDITION_TITLE.matcher(normalizedTitle).find() ? "CONDITION" : null;
                    // A numbered exception/sub-clause is not a new unrelated chapter. Preserve it.
                    if (next != null || explicit && OTHER_SECTION_TITLE.matcher(title).matches()) {
                        flush(result, source, part.location(), partCategory, partHeading, text);
                        category = next;
                        heading = title;
                        partCategory = category;
                        partHeading = heading;
                    }
                }
                if (partCategory != null) text.append(line).append('\n');
            }
            flush(result, source, part.location(), partCategory, partHeading, text);
        }
        return result;
    }

    private static void flush(List<Segment> result, Source source, String location, String category, String heading, StringBuilder text) {
        if (category != null && !text.isEmpty())
            result.add(new Segment(category, heading, source, location, text.toString().strip()));
        text.setLength(0);
    }

    private static void validateReferences(Source source, List<Segment> selected, Set<String> issues) {
        var physicalPages = new HashSet<Integer>();
        var printedPages = new HashSet<Integer>();
        for (Part part : source.context()) {
            var matcher = Pattern.compile("(?:공고문\\s*)?(\\d+)쪽").matcher(part.location());
            while (matcher.find()) physicalPages.add(Integer.parseInt(matcher.group(1)));
            // Booklets may print two numbered pages on one PDF page. Body references use the
            // printed numbers; evidence links still use the physical Part location.
            var footer = Pattern.compile("(?m)^\\s*-\\s*(\\d{1,3})\\s*-\\s*$").matcher(part.text());
            while (footer.find()) printedPages.add(Integer.parseInt(footer.group(1)));
        }
        var pages = printedPages.isEmpty() ? physicalPages : printedPages;
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

    private static Evidence evidence(Segment s, String day) {
        if (s.replacement() != null) return s.replacement();
        return new Evidence(s.source().label() + " · " + s.location(), EvidenceLocationUrl.forSource(s.source(), s.location(), s.text()), day);
    }

    private static boolean valid(Evidence evidence) {
        try {
            EvidenceValidation.validate(evidence);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", "");
    }

    private static String id(String type, String value) {
        return "e-" + type + "-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean trusted(Source source) {
        try {
            URI url = URI.create(source.url());
            return source.status().equals("EXTRACTED") && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
                    && !source.context().isEmpty() && "https".equals(url.getScheme()) && Set.of("apply.lh.or.kr", "www.lh.or.kr").contains(url.getHost())
                    && url.getRawUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static AutomaticGuidanceCompiler.Result hold(String issue) {
        return new AutomaticGuidanceCompiler.Result(false, List.of(issue), null);
    }
}
