package org.jibro.collection.review;

import static org.jibro.notice.HousingModels.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns complete, source-verified applicant criteria into situations, without deciding eligibility.
 * Unrecognized recipients and exceptions keep their original questions and document rules.
 */
final class ApplicantSituationCompiler {
    static final String SITUATION = "applicant-situation";
    private static final String RECHECK = "applicant-situation-recheck";
    private static final Pattern ELIGIBILITY = Pattern.compile("(?:신청|입주)\\s*자격$");
    private static final Pattern PENDING = Pattern.compile("별도|추후|문의|확인\\s*필요|상동|아래|위와|다음|별표|참조|확인하세요|확인해");
    private static final List<AnswerOption> ACKNOWLEDGEMENT = List.of(new AnswerOption("ACK", "안내를 확인했어요"),
        new AnswerOption("UNKNOWN", "잘 모르겠어요", true));

    private ApplicantSituationCompiler() {
    }

    static Content compile(Content candidate) {
        return compile(candidate, Map.of());
    }

    static Content compile(Content candidate, Map<String, List<String>> declaredGroups) {
        if (candidate == null || candidate.questions().stream().anyMatch(q -> q.id().equals(SITUATION)))
            return candidate;
        boolean scopedInput = candidate.questions().stream().anyMatch(q -> q.applicability() != null);
        if (candidate.reviewIssues().stream().anyMatch(i -> "CONDITION".equals(i.area()) && i.questionIds().isEmpty()))
            return candidate;
        var missingTypes = OfficialGuidanceItem.missingApplicantTypes(candidate.questions().stream().map(Question::reason).toList(),
            candidate.questions().stream().map(Question::title).toList());
        // An incomplete type catalogue must not fall back to a different (for example adult/minor) selector.
        if (!missingTypes.isEmpty()) return candidate;
        var typed = types(candidate, declaredGroups);
        if (typed != candidate) return typed;
        return scopedInput ? candidate : adult(candidate);
    }

    private record Criterion(String type, Question question, String value, boolean concrete) {
    }

    private static Content types(Content content, Map<String, List<String>> declaredGroups) {
        var criteria = new LinkedHashMap<String, Criterion>();
        for (var q : content.questions()) {
            String reviewedTitle = q.title().replaceFirst("\\s*확인\\s*필요$", "");
            if (protectedQuestion(content, q) && OfficialGuidanceItem.applicantType(reviewedTitle) != null
                && ELIGIBILITY.matcher(reviewedTitle).find()) return content;
            String type = OfficialGuidanceItem.applicantType(q.title());
            if (type == null || !ELIGIBILITY.matcher(q.title()).find()) continue;
            String value = criterion(q);
            // An unresolved or partial type must not disappear from a generated selector.
            if (protectedQuestion(content, q) || value == null || unresolvedReference(value)) return content;
            boolean concrete = q.title().length() + value.length() + 3 <= 300;
            var previous = criteria.putIfAbsent(type, new Criterion(type, q, value, concrete));
            if (previous != null && !compact(previous.value()).equals(compact(value))) return content;
        }
        if (criteria.size() < 2) return content;
        if (content.questions().stream().anyMatch(q -> q.options() != null
            && q.options().stream().map(AnswerOption::value).filter(criteria::containsKey).distinct().count() > 1))
            return content;
        String detail = "신청할 유형을 선택하세요. 해당 유형의 세부 기준과 예외는 이어지는 질문에서 확인해요.\n\n"
            + String.join("\n", criteria.values().stream().map(c -> c.question().title()).toList());
        var options = new ArrayList<AnswerOption>();
        criteria.values().forEach(c -> options.add(new AnswerOption(c.type(),
            c.question().title().replaceFirst("\\s*(?:신청|입주)\\s*자격$", "") + (c.concrete() ? " · " + c.value() : "으로 신청해요"))));
        options.add(new AnswerOption("NONE", "어느 상황에도 해당하지 않아요"));
        options.add(new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
        var parent = new Question(SITUATION, "어떤 신청 상황에 해당하나요?", detail,
            criteria.values().iterator().next().question().evidence(), options);
        var questions = new ArrayList<Question>();
        questions.add(parent);
        questions.add(recheck(parent.evidence()));
        var removable = new HashSet<String>();
        var explanations = new HashSet<String>();
        var documents = new ArrayList<DocumentTemplate>();
        var groups = new LinkedHashMap<>(applicantGroups(content, criteria.keySet()));
        for (var entry : declaredGroups.entrySet()) {
            if (!criteria.keySet().containsAll(entry.getValue())) return content;
            var old = groups.putIfAbsent(compact(entry.getKey()), entry.getValue());
            if (old != null && !new HashSet<>(old).equals(new HashSet<>(entry.getValue()))) return content;
        }
        for (var document : content.documents()) {
            var question = content.questions().stream().filter(q -> q.id().equals(document.conditionQuestionId())).findFirst().orElse(null);
            var scope = question == null || protectedQuestion(content, question) || protectedDocument(content, document)
                || hasReferences(content, question.id(), true)
                ? List.<String>of() : documentScope(document, question, criteria.keySet(), groups);
            if (scope.isEmpty()) documents.add(document);
            else {
                documents.add(new DocumentTemplate(document.id(), document.title(), SITUATION, document.method(), document.issuer(),
                    document.issueUrl(), document.evidence(), scope, document.issueLabel()));
                removable.add(question.id());
            }
        }
        for (var q : content.questions()) {
            if (removable.contains(q.id()) && documents.stream().noneMatch(d -> q.id().equals(d.conditionQuestionId())))
                continue;
            var scope = OfficialGuidanceItem.applicantTypes(q.title());
            if (scope.isEmpty() && !hasReferences(content, q.id(), true))
                scope = conditionalDocumentScope(content, q, criteria.keySet(), groups);
            if (q.applicability() != null) {
                if (q.applicability().questionId().equals(SITUATION)) {
                    if (!criteria.keySet().containsAll(q.applicability().answerValues())) return content;
                } else if (!scope.isEmpty()) return content;
                questions.add(q);
                continue;
            }
            if (protectedQuestion(content, q) || scope.isEmpty() || !criteria.keySet().containsAll(scope)) {
                questions.add(q);
                continue;
            }
            String type = scope.get(0);
            if (scope.size() == 1 && ELIGIBILITY.matcher(q.title()).find() && criteria.get(type).concrete()
                && !hasReferences(content, q.id(), false)) {
                // The situation answers this criterion; keep its full explanation and separate source link.
                if (explanations.add(type))
                    questions.add(new Question("applicant-criteria-" + type.toLowerCase(Locale.ROOT), q.title(), q.reason(), q.evidence(),
                        ACKNOWLEDGEMENT, "SINGLE", null, false, List.of(), new QuestionApplicability(SITUATION, scope)));
            } else questions.add(withScope(q, scope));
        }
        return copy(content, questions, documents);
    }

    private static String criterion(Question question) {
        String value = question.reason().strip();
        String prefix = question.title();
        if (value.startsWith(prefix))
            value = value.substring(prefix.length()).replaceFirst("^\\s*[:：]\\s*", "").strip();
        // These are complete explicit fields emitted by the rule compiler, not section previews.
        if (value.isEmpty() || value.startsWith("원문 구간") || value.contains("\n")) return null;
        return value;
    }

    private static boolean unresolvedReference(String value) {
        String inspected = value.replaceAll("다음\\s*학기", "학기")
            .replaceAll("\\(이하\\s*이\\s*별표에서\\s*[^()]{1,40}라\\s*한다\\)", "");
        // The entire field owns this numbered list. Recognize its local references only when
        // all advertised entries occur exactly once, in order, after the reference sentence.
        var listed = Pattern.compile("(?:아래|다음)\\s*((?:[1-9]\\)\\s*[,，]\\s*)*[1-9]\\))\\s*중\\s*하나").matcher(inspected);
        if (listed.find()) {
            var labels = Pattern.compile("[1-9]\\)").matcher(listed.group(1)).results().map(m -> m.group()).toList();
            String body = inspected.substring(listed.end());
            var actual = Pattern.compile("(?<![0-9])[1-9]\\)").matcher(body).results().map(m -> m.group()).toList();
            if (labels.equals(actual)) inspected = inspected.substring(0, listed.start()) + "열거 항목 중 하나" + body;
        }
        var local = Pattern.compile("다음의\\s*어느\\s*하나").matcher(inspected);
        if (local.find()) {
            var numbers = Pattern.compile("(?<![0-9])([1-9])\\)").matcher(inspected.substring(local.end())).results()
                .map(m -> Integer.parseInt(m.group(1))).toList();
            if (numbers.size() >= 2 && java.util.stream.IntStream.range(0, numbers.size()).allMatch(i -> numbers.get(i) == i + 1))
                inspected = local.replaceFirst("열거 항목 중 하나");
        }
        return PENDING.matcher(inspected).find();
    }

    private static Map<String, List<String>> applicantGroups(Content content, Set<String> types) {
        var groups = new LinkedHashMap<String, List<String>>();
        var ambiguous = new HashSet<String>();
        for (var q : content.questions()) {
            if (protectedQuestion(content, q)) continue;
            var headings = q.reason().lines().map(OfficialGuidanceItem::applicantSection).filter(Objects::nonNull).toList();
            if (headings.size() != 1) continue;
            var numbered = OfficialGuidanceItem.numberedApplicantFields(q.reason());
            // Returned identity fields require every referenced numbered clause to be present.
            // A long final clause may stay in the original explanation instead of becoming a
            // separate field; that does not change the explicitly enumerated group membership.
            var members = numbered.statements().stream().map(OfficialGuidanceItem::parse).filter(Objects::nonNull)
                .filter(r -> ELIGIBILITY.matcher(r.title()).find())
                .flatMap(r -> OfficialGuidanceItem.applicantTypes(r.title()).stream()).distinct().toList();
            if (members.size() < 2 || !types.containsAll(members)) continue;
            String name = compact(headings.get(0));
            var old = groups.putIfAbsent(name, members);
            if (old != null && !new HashSet<>(old).equals(new HashSet<>(members))) ambiguous.add(name);
        }
        ambiguous.forEach(groups::remove);
        return groups;
    }

    private static List<String> documentScope(DocumentTemplate document, Question question, Set<String> types,
                                              Map<String, List<String>> groups) {
        // Additional recipient conditions, alternatives and exemptions still need their own question.
        if (OfficialGuidanceRows.limitedSubmission(document.method())) return List.of();
        String recipient = document.method().split(" · (?:발급처|안내): ", 2)[0].strip();
        var scope = OfficialGuidanceItem.applicantTypes(recipient + " 신청자격");
        if (scope.isEmpty() && recipient.endsWith("공통"))
            scope = groups.getOrDefault(compact(recipient.substring(0, recipient.length() - 2)), List.of());
        if (scope.isEmpty() || !types.containsAll(scope) || question.answerOptions().stream().noneMatch(o -> o.value().equals("YES"))
            || !List.of("YES").equals(document.conditionAnswerValues())) return List.of();
        String questionType = OfficialGuidanceItem.applicantType(question.title());
        if (questionType != null && !scope.equals(List.of(questionType))) return List.of();
        return scope;
    }

    private static List<String> conditionalDocumentScope(Content content, Question question, Set<String> types,
                                                         Map<String, List<String>> groups) {
        var linked = content.documents().stream().filter(d -> question.id().equals(d.conditionQuestionId())).toList();
        if (linked.isEmpty() || linked.stream().anyMatch(d -> protectedDocument(content, d))) return List.of();
        List<String> scope = null;
        for (var document : linked) {
            String recipient = document.method().split(" · (?:발급처|안내): ", 2)[0].strip();
            String[] restricted = recipient.split("\\s+중\\s+", 2);
            List<String> actual;
            if (restricted.length == 2 && !restricted[1].isBlank())
                actual = OfficialGuidanceItem.applicantTypes(restricted[0] + " 신청자격");
            else if (recipient.endsWith("해당자만 제출"))
                actual = groups.getOrDefault(compact(recipient.substring(0, recipient.length() - "해당자만 제출".length())), List.of());
            else {
                var qualified = Pattern.compile("^([^()]+)\\s*\\((?:만\\s*)?[0-9]{1,3}세\\s*이상\\s*[~∼～]\\s*(?:만\\s*)?[0-9]{1,3}세\\s*이하\\)$").matcher(recipient);
                actual = qualified.matches() ? OfficialGuidanceItem.applicantTypes(qualified.group(1).strip() + " 신청자격") : List.of();
                if (actual.isEmpty() && OfficialGuidanceRows.limitedSubmission(document.method())) {
                    actual = OfficialGuidanceItem.applicantTypes(recipient + " 신청자격");
                    if (actual.isEmpty() && recipient.endsWith("공통"))
                        actual = groups.getOrDefault(compact(recipient.substring(0, recipient.length() - 2)), List.of());
                }
            }
            if (actual.isEmpty() || !types.containsAll(actual) || scope != null && !scope.equals(actual))
                return List.of();
            scope = actual;
        }
        return scope;
    }

    private static Question withScope(Question q, List<String> values) {
        return new Question(q.id(), q.title(), q.reason(), q.evidence(), q.options(), q.inputKind(), q.constraints(),
            q.reviewRequired(), q.answerDependencies(), new QuestionApplicability(SITUATION, values));
    }

    private static boolean hasReferences(Content content, String id, boolean ignoreDocument) {
        return !ignoreDocument && content.documents().stream().anyMatch(d -> id.equals(d.conditionQuestionId()))
            || content.questions().stream().anyMatch(q -> q.applicability() != null && id.equals(q.applicability().questionId()))
            || content.questions().stream().anyMatch(q -> q.id().equals(id) && !q.answerDependencies().isEmpty()
            || q.answerDependencies().stream().anyMatch(d -> id.equals(d.questionId())))
            || content.reviewIssues().stream().anyMatch(i -> i.questionIds().contains(id));
    }

    private static boolean protectedQuestion(Content content, Question question) {
        return question.reviewRequired() || content.reviewIssues().stream().anyMatch(i -> i.questionIds().contains(question.id()));
    }

    private static boolean protectedDocument(Content content, DocumentTemplate document) {
        return content.reviewIssues().stream().anyMatch(i -> i.documentIds().contains(document.id())
            || "DOCUMENT".equals(i.area()) && i.documentIds().isEmpty() && i.questionIds().isEmpty());
    }

    private static Content adult(Content content) {
        // Candidate text already reflects verified corrections. Never reintroduce superseded draft prose.
        if (content.questions().stream().anyMatch(q -> protectedQuestion(content, q)
            && Pattern.compile("성년|연령|나이|(?:신청|입주)\\s*자격").matcher(q.title()).find())) return content;
        var spans = new LinkedHashMap<String, Question>();
        for (var q : content.questions()) {
            if (protectedQuestion(content, q)) continue;
            String text = q.reason();
            var start = Pattern.compile("[■□]\\s*성년자|[•ㆍ※]\\s*「민법」상\\s*미성년자\\s*\\(").matcher(text);
            if (!start.find()) continue;
            int end = text.indexOf('■', start.end());
            String span = text.substring(start.group().contains("성년자") && !start.group().contains("미성년자")
                ? start.end() : start.start(), end < 0 ? text.length() : end).strip();
            if (span.length() <= 1800) spans.putIfAbsent(span, q);
        }
        if (spans.size() != 1) return content;
        String span = spans.keySet().iterator().next(), plain = compact(span);
        if (Pattern.compile("(?:기관|담당자|관리사무소)(?:에|로|에게).{0,5}(?:확인|문의)").matcher(plain).find()) return content;
        var source = spans.values().iterator().next();
        var age = Pattern.compile("(?:만)?([0-9]{1,3})세미만").matcher(plain);
        if (!age.find() || !plain.contains("미성년자도") || !plain.contains("신청가능")
            || plain.contains("신청가능하지")
            || !plain.contains("자녀가있는미성년세대주")
            || !plain.contains("직계존속의사망") || !plain.contains("실종") || !plain.contains("행방불명")
            || !Pattern.compile("형제[·,]?자매를부양").matcher(plain).find()
            || !(plain.contains("외국인부모") || plain.contains("부또는모가외국인"))
            || !plain.contains("내국인") || !plain.contains("한부모가족")) return content;
        String years = age.group(1);
        if (age.find()) return content;
        var evidence = source.evidence();
        String childHousehold = Pattern.compile("주민등록표(?:등본)?(?:상)?에자녀가등재").matcher(plain).find() ? "같은 등본에 있는 " : "";
        String siblingHousehold = plain.contains("형제자매는동일한세대별주민등록표")
            || Pattern.compile("동일한세대별주민등록표등본에형제[·,]?자매가등재되어있어야함").matcher(plain).find()
            ? "같은 등본의 " : "";
        var options = List.of(new AnswerOption("ADULT", "만 " + years + "세 이상이에요"),
            new AnswerOption("MINOR_CHILD", childHousehold + "자녀를 부양하는 미성년 세대주예요"),
            new AnswerOption("MINOR_SIBLINGS", "부모·조부모 등의 사망·실종·행방불명으로 " + siblingHousehold + "형제자매를 부양하는 미성년 세대주예요"),
            new AnswerOption("MINOR_FOREIGN_PARENT", "외국인 부모를 둔 한부모가족의 한국 국적 미성년 세대주예요"),
            new AnswerOption("NONE", "어느 상황에도 해당하지 않아요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
        var questions = new ArrayList<Question>();
        questions.add(new Question(SITUATION, "신청자는 어떤 상황인가요?", span, evidence, options));
        questions.add(recheck(evidence));
        questions.add(withScope(new Question("applicant-minor-sibling-cause", "형제자매를 부양하게 된 사유는 무엇인가요?", span, evidence,
            List.of(new AnswerOption("DEATH", "부모·조부모 등이 사망했어요"), new AnswerOption("DISAPPEARANCE",
                    plain.contains("실종선고") ? "부모·조부모 등에 대한 실종선고를 받았어요" : "부모·조부모 등이 실종됐어요"),
                new AnswerOption("MISSING", "부모·조부모 등이 행방불명됐어요"), new AnswerOption("OTHER", "다른 사유라 기관 확인이 필요해요", true),
                new AnswerOption("UNKNOWN", "잘 모르겠어요", true))), List.of("MINOR_SIBLINGS")));
        if (Pattern.compile("외국인부모가대리(?:하여)?신청").matcher(plain).find())
            questions.add(withScope(new Question("applicant-minor-parent-proxy", "외국인 부모가 대신 신청하나요?", span, evidence,
                List.of(new AnswerOption("YES", "외국인 부모가 대신 신청해요"), new AnswerOption("NO", "신청 방법을 기관에 확인해야 해요", true),
                    new AnswerOption("UNKNOWN", "잘 모르겠어요", true))), List.of("MINOR_FOREIGN_PARENT")));
        if (plain.matches("(?s).*법정대리인(?:의)?동의또는대리필요.*"))
            questions.add(withScope(new Question("applicant-minor-consent", "법정대리인의 동의나 대리 신청을 준비했나요?", span, evidence,
                List.of(new AnswerOption("YES", "동의나 대리 신청을 준비했어요"), new AnswerOption("NO", "준비 방법을 더 확인해야 해요", true),
                    new AnswerOption("UNKNOWN", "잘 모르겠어요", true))), List.of("MINOR_CHILD", "MINOR_SIBLINGS", "MINOR_FOREIGN_PARENT")));
        for (var q : content.questions()) {
            boolean replacedAge = q.title().matches("성년자|성년\\s*기준|연령\\s*기준")
                && compact(q.reason()).replaceFirst("^[■□]성년자", "").equals(plain)
                && !hasReferences(content, q.id(), false) && !protectedQuestion(content, q);
            if (!replacedAge) questions.add(q);
        }
        var documents = new ArrayList<>(content.documents());
        if (Pattern.compile("행방불명의경우신고접수증으로증빙(?:해야|하여야|합니다|해요)").matcher(plain).find()
            || plain.contains("행방불명(신고접수증으로증빙)")) {
            var matching = documents.stream().filter(d -> compact(d.title()).equals("행방불명신고접수증")
                && EvidenceLocationUrl.base(d.evidence().url()).equals(EvidenceLocationUrl.base(evidence.url()))).toList();
            if (!matching.isEmpty()) {
                if (matching.size() != 1 || protectedDocument(content, matching.get(0))) return content;
                var report = matching.get(0);
                var previous = content.questions().stream().filter(q -> q.id().equals(report.conditionQuestionId())).findFirst();
                // A similarly named proof may also serve household exclusion or another recipient.
                // Do not replace a shared/conditional rule merely because the document name matches.
                if (previous.isEmpty() || protectedQuestion(content, previous.get()) || !sameMinorRecipient(report)
                    || hasReferences(content, previous.get().id(), true)
                    || content.documents().stream().anyMatch(d -> !d.id().equals(report.id()) && previous.get().id().equals(d.conditionQuestionId())))
                    return content;
                documents.set(documents.indexOf(report), new DocumentTemplate(report.id(), report.title(), "applicant-minor-sibling-cause",
                    report.method(), report.issuer(), report.issueUrl(), report.evidence(), List.of("MISSING"), report.issueLabel()));
                questions.removeIf(q -> q.id().equals(previous.get().id()));
            } else {
                documents.add(new DocumentTemplate("applicant-minor-missing-report", "행방불명 신고접수증", "applicant-minor-sibling-cause",
                    "부모·조부모 등의 행방불명으로 " + siblingHousehold + "형제자매를 부양하는 미성년 세대주는 신고접수증으로 증빙해요.",
                    "공식 공고의 제출 안내 확인", null, evidence, List.of("MISSING")));
            }
        }
        return copy(content, questions, documents);
    }

    private static boolean sameMinorRecipient(DocumentTemplate document) {
        if (!List.of("YES").equals(document.conditionAnswerValues())) return false;
        String recipient = compact(document.method().split(" · 발급처: ", 2)[0]);
        return recipient.matches("(?:(?:부모·조부모등|직계존속)의)?행방불명으로"
            + "(?:(?:같은등본(?:에있는|의)|동일한세대별주민등록표(?:상)?에등재된))?"
            + "형제자매를부양(?:해야)?하는미성년세대주[.。]?");
    }

    private static Question recheck(Evidence evidence) {
        return withScope(new Question(RECHECK, "해당하는 신청 상황을 다시 확인해 주세요", "공고의 신청 대상과 예외를 다시 확인하고, 해당 여부가 불명확하면 모집기관에 확인해 주세요.", evidence,
            List.of(new AnswerOption("REVIEW", "신청 대상이나 예외를 확인할게요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))), List.of("NONE"));
    }

    private static Content copy(Content original, List<Question> questions, List<DocumentTemplate> documents) {
        return new Content(original.summary(), original.evidence(), original.attachments(), List.copyOf(questions), List.copyOf(documents),
            original.schedules(), original.reviewIssues(), original.applicationClosure());
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", "");
    }
}
