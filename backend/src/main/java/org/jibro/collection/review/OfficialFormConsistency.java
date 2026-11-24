package org.jibro.collection.review;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;

/**
 * Checks a form's explicit reference to this recruitment, not arbitrary historical/legal dates.
 */
public final class OfficialFormConsistency {
    private OfficialFormConsistency() {
    }

    private static final Pattern PUBLICATION = Pattern.compile("^(?:[（(]?(?:입주자모집공고일|공고일))[:：]?[（(]?(20\\d{2})[.년/-](\\d{1,2})[.월/-](\\d{1,2})(?:[.일）)].*)?$");
    private static final Pattern FORM_REFERENCE = Pattern.compile("(?:공고[（(]|\\[신청주택[:：][^\\[\\]]*?[（(])[‘’'`]?((?:20)?\\d{2})[.년/-](\\d{1,2})[.월/-](\\d{1,2})[.일]?(?:[）)]에따른입주자로서|공고[）)]\\])");

    public static List<GuidanceFinding> inspect(Draft draft) {
        return inspect(draft, List.of());
    }

    static List<GuidanceFinding> inspect(Draft draft, List<Source> agencyForms) {
        if (draft == null || draft.sources() == null || draft.checkedAt() == null) return List.of();
        var dates = new LinkedHashSet<LocalDate>();
        for (Source source : draft.sources()) {
            // EMPTY means no classified excerpt, not necessarily an unread listing. Only the
            // independently verified metadata grammar can supply anchors in that state.
            boolean verifiedEmptyListing = "EMPTY".equals(source.status()) && OfficialListingMetadata.onlyFields(source);
            if (!"EXTRACTED".equals(source.status()) && !verifiedEmptyListing) continue;
            // A legal/example date deep in an attachment is not the current recruitment date.
            // Listing field parts and standalone first-page labels are the only primary anchors.
            boolean listing = compact(source.label()).equals("공식상세본문");
            var anchors = listing ? source.context() : source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                ? source.context().stream().filter(p -> p.location().matches("(?:공고문\\s*)?1\\s*쪽")).toList() : List.<Part>of();
            for (Part part : anchors) {
                for (String line : part.text().split("\\R")) {
                    var matcher = PUBLICATION.matcher(compact(line));
                    if (matcher.matches()) {
                        LocalDate date = date(matcher.group(1), matcher.group(2), matcher.group(3));
                        if (date != null) dates.add(date);
                    }
                }
            }
        }
        var findings = new ArrayList<GuidanceFinding>();
        String checked = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        if (dates.size() > 1) {
            Source source = draft.sources().get(0);
            findings.add(new GuidanceFinding("PUBLICATION_DATE_CONFLICT", "본문의 모집공고일이 서로 다릅니다: " + dates,
                new Evidence(source.label(), source.url(), checked)));
        }
        if (dates.size() != 1) return List.copyOf(findings);
        LocalDate publication = dates.iterator().next();
        for (Source source : java.util.stream.Stream.concat(draft.sources().stream(), agencyForms.stream()).toList()) {
            if (!"EXTRACTED".equals(source.status())) continue;
            for (Part part : source.context()) {
                String text = compact(part.text());
                if (!text.contains("확약서") && !text.contains("확인서")) continue;
                var matcher = FORM_REFERENCE.matcher(text);
                while (matcher.find()) {
                    LocalDate referenced = date(matcher.group(1), matcher.group(2), matcher.group(3));
                    if (referenced == null || !publication.equals(referenced))
                        findings.add(new GuidanceFinding("FORM_PUBLICATION_DATE_MISMATCH",
                            "모집공고일은 " + publication + "이지만 첨부 서식은 " + (referenced == null ? matcher.group() : referenced)
                                + " 공고를 가리킵니다. 날짜를 임의로 고치지 않고 기관 확인이 필요합니다.",
                            new Evidence(source.label() + " · " + part.location(), EvidenceLocationUrl.forSource(source, part.location(), part.text().lines()
                                .filter(line -> FORM_REFERENCE.matcher(compact(line)).find()).findFirst().orElse("")), checked)));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year.length() == 2 ? "20" + year : year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", "");
    }

    static AutomaticGuidanceCompiler.Result instructions(Content content, List<VerifiedPreparationForms.Block> blocks,
                                                         String checked) {
        if (blocks.isEmpty()) return new AutomaticGuidanceCompiler.Result(true, List.of(), content);
        var documents = new ArrayList<>(content.documents());
        var questions = new ArrayList<>(content.questions());
        var review = new ArrayList<>(content.reviewIssues());
        var blockers = new ArrayList<String>();
        for (var block : blocks) {
            var evidence = new Evidence(block.source().label() + " · " + block.title(),
                EvidenceLocationUrl.forSource(block.source(), block.location(), block.title()), checked);
            var matches = documents.stream().filter(doc -> refers(doc, block)).toList();
            if (matches.isEmpty()) {
                blockers.add("FORM_DOCUMENT_REFERENCE_UNMAPPED:" + block.number());
                continue;
            }
            for (var doc : matches) {
                String audience = compact(block.recipient());
                boolean common = audience.equals("신청자전원필수제출");
                String type = audience.endsWith("만제출")
                    ? OfficialGuidanceItem.applicantType(audience.substring(0, audience.length() - 3) + " 신청자격") : null;
                var parent = content.questions().stream().filter(q -> q.id().equals(doc.conditionQuestionId())).findFirst().orElse(null);
                boolean protectedItem = parent != null && parent.reviewRequired() || content.reviewIssues().stream()
                    .anyMatch(issue -> issue.area().equals("DOCUMENT") && (issue.documentIds().isEmpty() || issue.documentIds().contains(doc.id())));
                boolean limited = OfficialGuidanceRows.limitedSubmission(doc.method() + " " + (parent == null ? "" : parent.reason()));
                boolean knownCommon = !protectedItem && !limited && common && doc.conditionQuestionId() == null;
                boolean knownType = type != null && parent != null && doc.conditionAnswerValues() != null
                    && doc.conditionAnswerValues().equals(List.of(type)) && parent.options() != null
                    && parent.options().stream().anyMatch(o -> o.value().equals(type));
                boolean proxy = audience.equals("대리인이현장접수하는경우에만제출")
                    && doc.title().contains("대리") && compact(doc.method()).contains("위임장");
                if (!knownCommon && !knownType && !proxy) {
                    review.add(new ReviewIssue("form-audience-" + java.util.UUID.nameUUIDFromBytes((doc.id() + "\n" + block.number()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "DOCUMENT", doc.title() + " 제출 대상 대조",
                        "모집공고와 첨부 서식의 제출 대상을 함께 확인해야 해요. 서식에는 ‘" + block.recipient() + "’라고 표시돼 있어요.",
                        parent == null ? List.of() : List.of(parent.id()), List.of(doc.id()), List.of(doc.evidence(), evidence)));
                }
                var primarySigning = guardianSigning(doc.method());
                var formSigning = guardianSigning(block.text() + "\n" + String.join("\n", block.instructions()));
                boolean uncertainSigning = java.util.stream.Stream.concat(primarySigning.keySet().stream(), formSigning.keySet().stream()).anyMatch(rule -> rule.signature() == null);
                if (uncertainSigning || !primarySigning.isEmpty() && !formSigning.isEmpty() && !primarySigning.keySet().equals(formSigning.keySet())) {
                    review.add(new ReviewIssue("form-signing-" + java.util.UUID.nameUUIDFromBytes((doc.id() + "\n" + block.number()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "DOCUMENT", doc.title() + " 보호자 서명 기준 대조",
                        (uncertainSigning ? "보호자(법정대리인) 서명 문구를 확정해서 읽지 못했어요. " : "같은 서류의 보호자(법정대리인) 서명 기준이 달라요. ")
                            + "모집공고: " + signingText(primarySigning.keySet()) + ". 첨부 서식: " + signingText(formSigning.keySet())
                            + ". 원문을 그대로 두고 기관 확인이 필요해요.",
                        List.of(), List.of(doc.id()), List.of(doc.evidence(), evidence)));
                }
                var written = new ArrayList<>(block.instructions());
                formSigning.values().stream().filter(clause -> written.stream().noneMatch(item -> compact(item).contains(compact(clause))))
                    .forEach(written::add);
                var instructions = written.stream().filter(p -> !compact(doc.method()).contains(compact(p))).toList();
                String method = doc.method() + (instructions.isEmpty() ? "" : "\n\n공식 서식 작성 안내\n" + String.join("\n", instructions));
                if (method.length() > 2000) {
                    blockers.add("FORM_INSTRUCTIONS_TOO_LONG:" + block.number());
                    continue;
                }
                documents.set(documents.indexOf(doc), new DocumentTemplate(doc.id(), doc.title(), doc.conditionQuestionId(), method, doc.issuer(),
                    block.source().url(), doc.evidence(), doc.conditionAnswerValues(), "공식 서식 · " + block.title()));
            }
        }
        var result = new Content(content.summary(), content.evidence(), content.attachments(), questions, documents,
            content.schedules(), review, content.applicationClosure());
        return new AutomaticGuidanceCompiler.Result(blockers.isEmpty(), List.copyOf(blockers), result);
    }

    /**
     * Materializes fully parsed semantic bundles before applicant scopes are compiled.
     */
    static AutomaticGuidanceCompiler.Result supplementaryInstructions(Content content, SupplementaryRules.Context context, String checked) {
        var documents = new ArrayList<>(content.documents());
        var questions = new ArrayList<>(content.questions());
        var review = new ArrayList<>(content.reviewIssues());
        var blockers = new LinkedHashSet<String>();
        if (!context.blockers().isEmpty())
            return new AutomaticGuidanceCompiler.Result(false, context.blockers(), content);
        String route = "application-channel", actor = "application-attendee", returning = "returning-student";
        var primaryEvidence = new Evidence(context.primary().source().label(), context.primary().source().url(), checked);
        boolean hasApplication = context.forms().stream().anyMatch(f -> f.role() == SupplementaryRules.Role.APPLICATION_FORM);
        if (hasApplication) questions.add(new Question(route, "어떤 방법으로 신청하나요?",
            "인터넷·모바일로 신청해요. 만 65세 이상·장애인 등 정보취약계층은 공고의 방문 접수도 이용할 수 있어요.", primaryEvidence,
            List.of(new AnswerOption("ONLINE", "인터넷·모바일로 신청해요"), new AnswerOption("VISIT", "방문 접수 대상이며 현장에서 신청해요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
        if (context.forms().stream().anyMatch(f -> f.role() == SupplementaryRules.Role.APPLICATION_FORM)) {
            String main = SupplementaryRules.text(context.primary().source());
            if (!main.contains("세대내다른구성원이주택을소유해도신청가능함")) blockers.add("FORM_HOUSING_COMPARISON_UNREAD");
            else {
                questions.add(new Question("applicant-own-home", "내 명의의 집이 있나요?", "대학생·청년 계층은 신청자 본인의 주택을 확인해요. 가족 명의의 집은 신청자 본인의 집과 구분해요. 분양권·입주권과 공고의 무주택 인정 예외도 함께 확인해요.", primaryEvidence,
                    List.of(new AnswerOption("NO_HOME", "집이 없거나 공고의 무주택 인정 예외에 해당해요"), new AnswerOption("HAS_HOME", "내 명의의 집이 있어요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)),
                    "SINGLE", null, false, List.of(), new QuestionApplicability(ApplicantSituationCompiler.SITUATION, List.of("STUDENT", "JOBSEEKER", "YOUTH", "EARLY_CAREER"))));
                questions.add(new Question("applicant-household-home", "함께 심사하는 가족 모두 집이 없나요?", "주거급여수급자는 신청자와 공고에서 정한 심사 대상 가족의 주택을 함께 확인해요. 주소가 다른 배우자 등 포함 가족과 분양권·입주권·무주택 인정 예외는 본문 기준을 따라요.", primaryEvidence,
                    List.of(new AnswerOption("NO_HOME", "심사 대상 가족 모두 집이 없거나 인정 예외에 해당해요"), new AnswerOption("HAS_HOME", "심사 대상 가족의 집이 있어요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)),
                    "SINGLE", null, false, List.of(), new QuestionApplicability(ApplicantSituationCompiler.SITUATION, List.of("HOUSING_BENEFIT"))));
            }
        }
        var attendance = documents.stream().filter(d -> compact(d.title()).endsWith("·구비서류")
            && compact(d.title()).contains("동호지정·계약체결할때")).toList();
        if (!attendance.isEmpty()) {
            if (attendance.size() != 3) blockers.add("FORM_ATTENDANCE_SCOPE_UNMAPPED");
            else {
                questions.add(new Question(actor, "신청·동호 지정·계약 때 누가 방문하나요?", "각 방문 때 본인·배우자·그 밖의 대리인 중 실제 방문하는 사람에 맞는 서류를 챙겨요. 방문하는 사람이 달라지면 답변도 바꿔 주세요.", primaryEvidence,
                    List.of(new AnswerOption("SELF", "내가 직접 가요"), new AnswerOption("SPOUSE", "배우자가 대신 가요"), new AnswerOption("PROXY", "다른 대리인이 대신 가요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
                for (var doc : attendance) {
                    String title = compact(doc.title());
                    String answer = title.contains("기타대리인") ? "PROXY" : title.contains("배우자가") ? "SPOUSE" : "SELF";
                    documents.set(documents.indexOf(doc), new DocumentTemplate(doc.id(), doc.title(), actor, doc.method(), doc.issuer(), doc.issueUrl(), doc.evidence(), List.of(answer), doc.issueLabel()));
                    removeUnusedInput(questions, documents, doc.conditionQuestionId());
                }
            }
        }
        for (var form : context.forms()) {
            var block = form.block();
            var source = block.source();
            var evidence = new Evidence(source.label() + " · " + block.title(), EvidenceLocationUrl.forSource(source, block.location(), block.title()), checked);
            if (form.role() == SupplementaryRules.Role.ACCESSIBILITY_REQUEST) {
                String submission = between(block.text(), "9. 제 출 서 류", "본인이 신청을 희망한 편의증진시설 내용을 확인하고", false);
                String proof = between(submission, "1)", "2)", false);
                String resident = between(block.text(), "2) 주민등록등본", "본인이 신청을 희망한 편의증진시설 내용을 확인하고", true);
                if (proof.isBlank() || resident.isBlank() || !addAccessibility(questions, documents, evidence, source.url(), proof, resident))
                    blockers.add("ACCESSIBILITY_BINDING_UNVERIFIED");
                continue;
            }
            var matches = documents.stream().filter(d -> switch (form.role()) {
                case RENEWAL_REFERENCE -> compact(d.title()).contains("입주자격완화") && compact(d.title()).contains("확약서");
                case WAITLIST -> compact(d.title()).contains("중복선정");
                case CONSENT -> compact(d.title()).contains("개인정보") && compact(d.title()).contains("동의서");
                case POST_VERIFICATION -> compact(d.title()).contains("선계약후검증");
                case APPLICATION_FORM -> compact(d.title()).equals("공급신청서") || compact(d.title()).equals("행복주택공급신청서");
                case RETURN_STUDY -> compact(d.title()).contains("복학각서");
                case PROXY_AUTHORITY -> compact(d.title() + d.method()).contains("위임장");
                default -> false;
            }).toList();
            String condition = null;
            List<String> answers = null;
            if (form.claims().stream().anyMatch(c -> c.scope().conditions().contains(SupplementaryRules.Condition.VISIT_APPLICATION))) {
                condition = route;
                answers = List.of("VISIT");
            }
            if (form.claims().stream().anyMatch(c -> c.scope().conditions().contains(SupplementaryRules.Condition.USES_THIS_PROXY_FORM))) {
                if (attendance.size() != 3) {
                    blockers.add("FORM_PROXY_RECIPIENT_UNMAPPED");
                    continue;
                }
                condition = actor;
                answers = List.of("PROXY");
            }
            if (form.claims().stream().anyMatch(c -> c.scope().conditions().contains(SupplementaryRules.Condition.RETURNING_STUDENT))) {
                if (content.questions().stream().noneMatch(q -> "STUDENT".equals(OfficialGuidanceItem.applicantType(q.title())) && q.title().endsWith("신청자격"))) {
                    blockers.add("FORM_RETURN_STUDENT_SCOPE_UNMAPPED");
                    continue;
                }
                questions.add(new Question(returning, "다음 학기에 복학할 예정인가요?", "복학 예정 대학생이면 휴학증명서와 각서를 준비해요. 이미 재학 중이거나 입학·졸업 예정인 경우에는 본인에게 해당하는 대학 서류를 준비해요.", evidence,
                    List.of(new AnswerOption("YES", "복학할 예정이에요"), new AnswerOption("NO", "복학 예정이 아니에요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)),
                    "SINGLE", null, false, List.of(), new QuestionApplicability(ApplicantSituationCompiler.SITUATION, form.claims().stream().flatMap(c -> c.scope().applicantTypes().stream()).distinct().toList())));
                condition = returning;
                answers = List.of("YES");
            }
            String instruction = form.claims().stream().map(OfficialFormConsistency::instruction).distinct().collect(java.util.stream.Collectors.joining("\n"));
            if (form.role() == SupplementaryRules.Role.RENEWAL_REFERENCE)
                instruction += "\n\n" + renewalNotice(context.primary().source());
            if (matches.isEmpty()) {
                String id = "form-" + form.role().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                var added = new DocumentTemplate(id, block.title(), condition, instruction, "LH 공식 서식", source.url(), evidence, answers, "공식 통합 서식 · " + block.title());
                documents.add(added);
                matches = List.of(added);
            } else for (var doc : matches) {
                // These bundles explicitly identify the recipient; an old noun-only self-assessment
                // must not keep a mandatory form optional or ask the same submission question twice.
                String method = doc.method().equals(instruction) ? instruction : doc.method() + "\n\n" + instruction;
                if (method.length() > 2000) {
                    blockers.add("FORM_INSTRUCTION_LIMIT:" + block.number());
                    continue;
                }
                String oldParent = doc.conditionQuestionId();
                documents.set(documents.indexOf(doc), new DocumentTemplate(doc.id(), doc.title(), condition, method, doc.issuer(), source.url(), doc.evidence(), answers, "공식 통합 서식 · " + block.title()));
                removeUnusedInput(questions, documents, oldParent);
            }
            var ids = matches.stream().map(DocumentTemplate::id).toList();
            if (form.role() == SupplementaryRules.Role.APPLICATION_FORM) {
                var main = SupplementaryRules.text(context.primary().source());
                if (!main.contains("세대내다른구성원이주택을소유해도신청가능함")) blockers.add("FORM_HOUSING_COMPARISON_UNREAD");
                else review.add(new ReviewIssue("form-household-declaration", "DOCUMENT", "현장 신청서의 무주택·자동차 서약 확인",
                    "본문은 대학생·청년 계층의 본인 주택 여부 등 유형별로 심사하지만 현장 신청서는 무주택세대구성원·차량 기준을 일괄 서약하게 되어 있어요. 본문 기준을 바꾸지 않으며 현장 신청서 작성 범위만 기관에 확인해요.", List.of(), ids, List.of(primaryEvidence, evidence)));
            }
            if (!form.signingYears().isEmpty() && form.signingYears().stream().anyMatch(y -> y != context.primary().publication().getYear())
                || !form.recipientOffices().isEmpty() && (context.primary().office() == null || form.recipientOffices().stream().anyMatch(o -> !o.equals(context.primary().office()))))
                review.add(new ReviewIssue("form-recipient-" + block.number(), "DOCUMENT", "서식의 연도·제출 기관 확인",
                    "현재 공고는 " + context.primary().publication() + " · " + context.primary().office() + "지역본부이지만 서식에는 " + form.signingYears() + " · " + form.recipientOffices() + "지역본부가 기재되어 있어요. 제출할 서식의 연도·수신 기관만 기관에 확인해요.", List.of(), ids, List.of(primaryEvidence, evidence)));
            if (form.role() == SupplementaryRules.Role.RETURN_STUDY) {
                String months = form.claims().stream().filter(c -> c.effect() == SupplementaryRules.Effect.RETURN_PROOF_WITHIN_MONTHS).findFirst().orElseThrow().operands().get("months");
                var mainMonths = Pattern.compile("복학후([0-9]+)개월이내에재학증명서").matcher(SupplementaryRules.text(context.primary().source())).results().map(m -> m.group(1)).distinct().toList();
                if (mainMonths.isEmpty()) blockers.add("FORM_RETURN_DEADLINE_UNREAD");
                else if (mainMonths.size() != 1 || !mainMonths.get(0).equals(months))
                    review.add(new ReviewIssue("form-return-deadline", "DOCUMENT", "복학 증빙 기한 확인", "본문은 복학 후 " + mainMonths + "개월 이내, 각서는 " + months + "개월 이내로 안내해요. 기한을 기관에 확인해요.", List.of(), ids, List.of(primaryEvidence, evidence)));
            }
        }
        var result = new Content(content.summary(), content.evidence(), content.attachments(), questions, documents, content.schedules(), review, content.applicationClosure());
        return new AutomaticGuidanceCompiler.Result(blockers.isEmpty(), List.copyOf(blockers), result);
    }

    private static void removeUnusedInput(List<Question> questions, List<DocumentTemplate> documents, String id) {
        if (id == null || documents.stream().anyMatch(d -> id.equals(d.conditionQuestionId()))) return;
        if (questions.stream().anyMatch(q -> q.applicability() != null && id.equals(q.applicability().questionId()) || q.answerDependencies().stream().anyMatch(d -> id.equals(d.questionId()))))
            return;
        questions.removeIf(q -> q.id().equals(id));
    }

    private static String renewalNotice(Source primary) {
        // The complete primary domain grammar above established these exemptions and their
        // conditions. Values come from its actual clauses, never from a different notice.
        String text = SupplementaryRules.text(primary);
        var rounds = Pattern.compile("→([0-9]+)회갱신시에는소득,자산초과시에도임대조건할증없이재계약가능하며,([0-9]+)회에는예비자가없을경우에만할증없이재계약허용됩니다.\\(([0-9]+)회차부터는일반조건충족시갱신가능\\)").matcher(text);
        var child = Pattern.compile("(20[0-9]{2})년([0-9]+)월([0-9]+)일이후출생\\(행복주택거주중출생한경우만해당\\)한자녀가있는경우,자산요건을초과하면공급대상자별임대보증금및임대료에([0-9]+)%").matcher(text);
        if (!rounds.find() || !child.find())
            throw new IllegalArgumentException("Unbound renewal operands in registered primary grammar");
        return "이 공고의 재계약 예외\n" + rounds.group(1) + "회 갱신은 소득·자산을 넘어도 할증 없이 가능해요. " + rounds.group(2) + "회는 적격 예비자가 없을 때만 할증 없이 가능하며, " + rounds.group(3) + "회부터는 일반 기준을 충족해야 해요.\n"
            + child.group(1) + "년 " + child.group(2) + "월 " + child.group(3) + "일 이후 행복주택에 살면서 태어난 자녀가 있으면 자녀가 성년이 될 때까지 무주택·자동차 기준을 지키며 재계약할 수 있어요. 이 경우 자산을 초과하면 " + child.group(4) + "% 할증을 적용하지만 자동차 초과는 이 예외에서 제외해요.";
    }

    private static String instruction(SupplementaryRules.Claim claim) {
        var n = claim.operands();
        String text = switch (claim.effect()) {
            case REFERENCE_TO_NOTICE_AND_LAW -> "재계약은 이 공고의 회차별 면제·예외와 관련 법령을 함께 적용해요.";
            case VACATE_IF_NOT_RENEWABLE -> "공고·법령에 따른 재계약 요건을 충족하지 못해 재계약이 불가하면 퇴거해요.";
            case PREMIUM_MAY_APPLY ->
                "공고·법령에서 거주를 허용하더라도 소득·자산 기준을 넘으면 임대료·보증금이 할증될 수 있어요. 이 공고의 면제 회차·출생 자녀 예외를 먼저 확인해요.";
            case RENEWAL_MAY_END -> "주택을 취득하면 재계약이 거절되거나 계약이 해지될 수 있어요.";
            case NO_DUPLICATE_SAME_TYPE -> "예비입주자 선정 때 같은 유형의 중복 선정은 불가해요.";
            case REMOVE_ALL_CONSTRUCTION_WAITLISTS ->
                "건설형 공공임대에 실제 입주하면 모든 건설형 입주대기 명부에서 제외돼요. 실제 입주일 또는 입주지정기간 뒤 보증금 완납일을 기준으로 해요.";
            case EXCLUDE_NOTICE_BEFORE_CONTRACT -> "대기 명부에서 제외될 때 계약 체결 전에 안내해요.";
            case CANCEL_WITHOUT_PENALTY_OR_INTEREST -> "입주자격 부적격이면 계약을 해지할 수 있어요. 계약금은 위약금·이자 없이 돌려줘요.";
            case MISSING_PROOF_PENALTY -> "입주자격 부적격이 아닌 보완서류 미제출은 공고에 정한 위약금 대상이에요.";
            case WITHDRAWAL_PENALTY -> "입주자격 부적격이 아닌 단순 변심 해약은 공고에 정한 위약금 대상이에요.";
            case PLAN_AFTER_FINAL_APPROVAL -> "최종 입주 가능 통보를 받은 뒤 이사 계획을 세워요.";
            case EARLY_MOVE_LOSS_CLAIM_RESTRICTION -> "최종 입주 가능 통보 전에 준비해 발생한 금전적 손실은 공사에 청구하지 않는다는 서약이에요.";
            case MANDATORY_CONSENT -> "해당 목적의 개인정보 동의는 필수예요.";
            case APPLICATION_OR_CONTRACT_MAY_BE_REFUSED -> "해당 필수 동의를 거부하면 신청·계약 또는 재계약이 거절될 수 있어요.";
            case GUARDIAN_SIGNATURE -> "동의서에 기재하는 만 " + n.get("belowAge") + "세 미만 세대원은 보호자가 서명해요.";
            case COLLECT_IDENTIFIER_UNDER_LAW -> "주민·외국인등록번호는 공고 서식에 기재된 법령에 따라 수집해요.";
            case OPTIONAL_SENSITIVE_CONSENT -> "민감정보 동의는 선택이에요.";
            case DISABILITY_PRIORITY_SCORE_FACILITY_EXCLUDED -> "민감정보 동의를 거부하면 장애인 우선공급·관련 배점·편의시설 대상에서 제외돼요.";
            case OPTIONAL_MARKETING_CONSENT -> "공공주택 정보 제공 동의는 선택이에요.";
            case MARKETING_REFUSAL_NO_HOUSING_SERVICE_LIMIT -> "정보 제공 동의를 거부해도 청약서비스 이용에는 제한이 없으며 안내 메시지를 받지 않아요.";
            case DELETE_WITH_LEGAL_RETENTION_EXCEPTION -> "목적 달성 후 " + n.get("days") + "일 이내 파기하되 법정 보존 의무가 있으면 예외예요.";
            case RETENTION_PERIOD ->
                "청약 신청자는 " + n.get("applicantMonths") + "개월, 당첨·예비자는 " + n.get("selectedYears") + "년 보유해요." + (n.containsKey("contractor") ? " 해당 목적의 계약자는 영구 보유해요." : "");
            case DECLARATION_SCOPE_REQUIRES_COMPARISON ->
                "방문 신청자만 작성해요. 차량가액 " + n.get("vehicleAmount") + "만원 이하·무주택세대 서약을 본문 유형별 심사 범위와 구분해요.";
            case RETURN_BY_NEXT_SEMESTER -> "입주지정기간 만료일 다음 학기까지 복학해요.";
            case RETURN_PROOF_WITHIN_MONTHS -> "복학 후 " + n.get("months") + "개월 이내 재학증명서를 내요.";
            case CANCEL_IF_RETURN_PROOF_MISSING -> "복학 또는 증빙 기한을 지키지 않으면 당첨·계약이 취소돼요.";
            case AUTHORIZE_PROXY -> "이 위임장을 쓰는 신청·동호 지정·계약 절차를 대리인에게 위임해요.";
            case PROXY_PROOFS ->
                "위임인은 자필로 작성해요. 본인 발급 인감증명서 1부와 본인·대리인 신분증 사본 각 1부를 붙여요. 본인서명사실확인서 방식은 공식 서식의 대체 방법을 따라요.";
            case PROXY_PARTIES_RESOLVE_DISPUTES -> "위임인·수임인 사이의 분쟁은 당사자가 해결하고 공사에 이의를 제기하지 않는다는 서약이에요.";
            case ACCESSIBILITY_ON_REQUEST -> "최초 입주자 본인·부양가족 중 공고의 주거약자가 있고 세대주가 희망하면 신청해요.";
            case MASK_RESIDENT_ID_SUFFIX -> "편의시설 신청용 등본은 주민번호 뒤 " + n.get("digits") + "자리를 가려요.";
        };
        String purpose = switch (claim.topic()) {
            case PRIVACY_BASIC -> "개인정보 수집·이용";
            case PRIVACY_IDENTIFIER -> "고유식별정보";
            case PRIVACY_SENSITIVE -> "민감정보";
            case PRIVACY_THIRD_PARTY -> "제3자 제공";
            case PRIVACY_MARKETING -> "공공주택 정보 제공";
            case PRIVACY_DELETION -> "개인정보 파기";
            default -> "";
        };
        String stage = switch (claim.scope().stage()) {
            case APPLICATION -> "신청";
            case SELECTION -> "대상자 선정";
            case CONTRACT -> "계약";
            case PRE_ENTRY -> "입주 준비";
            case ENTRY -> "입주";
            case RESIDENCE -> "거주 중";
            case RENEWAL -> "재계약";
            case DATA_PROCESSING -> "정보 처리";
        };
        return stage + " · " + claim.scope().subject() + " · " + (purpose.isEmpty() ? text : purpose + " · " + text);
    }

    private static String between(String text, String first, String last, boolean includeHeading) {
        var plain = new StringBuilder();
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            plain.append(c);
            offsets.add(i);
        }
        String begin = compact(first), finish = compact(last);
        int start = plain.indexOf(begin);
        int end = start < 0 ? -1 : plain.indexOf(finish, start + begin.length());
        return end < 0 ? "" : text.substring(offsets.get(includeHeading ? start : start + begin.length()), offsets.get(end)).strip();
    }

    private static boolean addAccessibility(List<Question> questions, List<DocumentTemplate> documents, Evidence evidence, String url,
                                            String proof, String resident) {
        String id = "accessibility-request";
        var expected = new Question(id, "장애인·고령자 편의시설 설치를 신청하나요?",
            "최초 입주자의 본인·부양가족 중 공고의 주거약자가 있고 세대주가 희망할 때 신청해요. 시설별 대상·규격과 설치 불가 예외는 붙임 서식에서 확인해요.",
            evidence, List.of(new AnswerOption("YES", "설치를 신청하려고 해요"), new AnswerOption("NO", "신청하지 않아요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)));
        var existing = questions.stream().filter(q -> q.id().equals(id)).findFirst();
        if (existing.isPresent() && !existing.get().equals(expected)) return false;
        if (existing.isEmpty()) questions.add(expected);
        for (var item : List.of(new String[]{"accessibility-form", "장애인·고령자 편의증진시설 설치신청서", "희망하는 시설을 표시하고 서명해요. 시설별 제공 대상과 현장 설치 가능 여부를 확인해요."},
            new String[]{"accessibility-proof", "편의시설 신청용 주거약자 증빙", "공식 편의시설 서식의 증빙 안내\n" + proof + "\n\n" + resident},
            new String[]{"accessibility-resident", "편의시설 신청용 주민등록등본", "일반 청약용 전부표기 등본과 용도를 구분해요.\n공식 편의시설 서식의 등본·개인정보 가림 안내\n" + resident})) {
            var document = new DocumentTemplate(item[0], item[1], id, item[2], "공고문 지정 기관", url, evidence, List.of("YES"), "공식 편의시설 서식");
            var previous = documents.stream().filter(d -> d.id().equals(item[0])).findFirst();
            if (previous.isPresent() && !previous.get().equals(document)) return false;
            if (previous.isEmpty()) documents.add(document);
        }
        return true;
    }

    private record GuardianSigning(int age, String limit, Boolean signature) {
    }

    private static java.util.Map<GuardianSigning, String> guardianSigning(String text) {
        var result = new java.util.LinkedHashMap<GuardianSigning, String>();
        // These are the age-qualified signing instructions of the same already-matched form.
        // Other ages, signers, legal citations and qualification limits are not interchangeable.
        var age = Pattern.compile("(?:만)?(\\d{1,3})세(미만|이하|이상|초과)");
        var signer = Pattern.compile("^(?:의)?(?:무주택세대구성원|세대구성원|세대원|신청자|사람|자)?"
            + "(?:은|는|의경우|인경우|이면|일경우)?(?:보호자(?:[（(]법정대리인[）)])?|법정대리인)(?:가|이|의)?(?:직접)?서명(?:또는날인)?(.*)$");
        for (String sentence : text.split("[.。;•※·\\r\\n]")) {
            String value = compact(sentence);
            var matches = age.matcher(value).results().toList();
            for (int i = 0; i < matches.size(); i++) {
                var current = matches.get(i);
                String following = value.substring(current.end(), i + 1 < matches.size() ? matches.get(i + 1).start() : value.length());
                var signed = signer.matcher(following);
                if (!signed.matches()) continue;
                String action = signed.group(1);
                Boolean required = switch (action) {
                    case "", "함", "합니다", "해야함", "해야합니다", "하여야합니다", "해야해요", "하세요" -> true;
                    case "하지않음", "하지않습니다", "하지않아요", "할필요없음", "할필요가없음", "할필요가없습니다",
                         "이필요없음", "이필요하지않습니다", "불필요", "은불필요" -> false;
                    default -> null;
                };
                result.putIfAbsent(new GuardianSigning(Integer.parseInt(current.group(1)), current.group(2), required), sentence.strip());
            }
        }
        return result;
    }

    private static String signingText(java.util.Set<GuardianSigning> values) {
        if (values.isEmpty()) return "명시한 연령 기준을 확인하지 못함";
        return values.stream().map(value -> value.age() + "세 " + value.limit() + " 보호자 서명 " + (value.signature() == null ? "여부 추가 확인" : value.signature() ? "필요" : "불필요"))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean refers(DocumentTemplate doc, VerifiedPreparationForms.Block block) {
        String key = VerifiedPreparationForms.key(doc.title()), form = VerifiedPreparationForms.key(block.title());
        if (key.equals(form)) return true;
        if (Pattern.compile("공사양식" + Pattern.quote(block.number()) + "(?![0-9-])").matcher(compact(doc.method())).find())
            return true;
        return form.equals("위임장") && doc.title().contains("대리") && compact(doc.method()).contains("위임장");
    }

}
