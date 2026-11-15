package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.EvidenceValidation;
import org.jibro.notice.HousingModels.*;

/**
 * A bounded, source-bound preparation draft for the relaxed national-rental template.
 * Publication still requires the independent coverage and complete-rule compiler checks.
 */
public final class NationalRentalGuidancePreview {
    public static final String PROFILE = "national-rental-waiver-v1";
    private static final Pattern CAR = Pattern.compile("개별자동차가액\\(?([0-9,]{1,12})\\)?만원이하");
    private NationalRentalGuidancePreview() {}
    public record Result(Content candidate, List<GuidanceFinding> findings) {}

    public static Result compile(Draft draft, Content original) {
        if (draft == null || draft.checkedAt() == null || !Set.of("READY", "PARTIAL").contains(draft.status())) return null;
        var matching = draft.sources().stream().filter(s -> "EXTRACTED".equals(s.status()) && s.sha256() != null
            && s.sha256().matches("[a-fA-F0-9]{64}") && s.label().toLowerCase().endsWith(".pdf")
            && !s.context().isEmpty() && s.context().size() <= 200 && s.context().stream().mapToLong(p -> p.text().length()).sum() <= 1_000_000)
            .filter(s -> {
                String front = NationalRentalSections.compact(s.context().get(0).text());
                return front.contains("국민임대주택") && front.contains("입주자격완화")
                    && front.contains("소득요건배제") && front.contains("총자산요건배제");
            }).toList();
        if (matching.size() != 1) return null;
        Source source = matching.get(0);
        if (!NationalRentalTemplateScope.matches(source)) return null;
        try { EvidenceValidation.validate(new Evidence(source.label(), source.url(), "2026-01-01")); }
        catch (RuntimeException invalid) { return null; }
        var sections = new NationalRentalSections(source, draft);
        if (!sections.contains("현장접수만가능", "거주지역,순위및배점등에상관없이", "신청접수순번대로",
                "세대구성원의범위", "금융정보", "제공동의서", "8.제출서류", "9.계약안내",
                "19세미만", "불법전대자입주자격제한", "4년", "소형·저가주택",
                "주민등록표등본", "예비입주자중복선정", "외국인은신청불가", "본인서명방식")) return null;
        var car = CAR.matcher(sections.compact());
        var amounts = new java.util.LinkedHashSet<String>();
        while (car.find()) amounts.add(car.group(1).replace(",", ""));
        if (amounts.size() != 1 || !sections.contains("비영업용승용자동차", "높은차량가액", "장애인사용자동차", "보철용차량", "보조금을제외")) return null;
        String amount;
        try { amount = String.format(java.util.Locale.ROOT, "%,d", Long.parseLong(amounts.iterator().next())); }
        catch (NumberFormatException invalid) { return null; }
        var questions = new ArrayList<Question>();
        var documents = new ArrayList<DocumentTemplate>();
        var eligibility = sections.evidence("성년자", "세대구성원의범위");
        questions.add(question("adult", "성년자이거나 공고의 미성년 신청 예외에 해당하나요?",
            "19세 이상이 기본입니다. 미성년 예외는 같은 등본의 자녀가 있는 세대주, 직계존속 사망·실종 등으로 같은 등본의 형제자매를 부양하는 세대주, 외국인 부모와 내국인 미성년 세대주인 한부모가족입니다. 마지막 경우 부모가 대리 신청합니다. 예외의 세부 요건과 입증자료는 원문에서 확인하세요.", eligibility,
            option("ADULT", "19세 이상이에요"), option("MINOR_CHILD", "자녀가 있는 미성년 세대주예요", true),
            option("MINOR_SIBLING", "형제자매를 부양하는 미성년 세대주예요", true), option("MINOR_PARENT", "외국인 부모와 내국인 미성년 한부모가족이에요", true), option("NONE", "해당하지 않아요", true)));
        questions.add(question("nationality", "신청자 본인의 국적을 확인했나요?", "공고는 외국인 본인의 신청을 제한합니다. 외국인 배우자·가족은 별도의 세대 범위와 등록 기준으로 확인합니다.", eligibility,
            option("DOMESTIC", "내국인이에요"), option("FOREIGN", "외국인이에요", true)));
        questions.add(question("household", "공고의 자격검증 대상 세대원을 모두 확인했나요?",
            "분리 배우자도 포함됩니다. 직계존속·직계비속과 그 배우자는 신청자 또는 분리 배우자의 등본 등재 조건이 다릅니다. 외국인 가족의 등록·주소, 태아, 예비신혼의 예정 세대도 구분하세요. 실종·행방불명·생계 및 주거 분리 등 제외 사유는 신청자의 입증이 필요합니다.", eligibility,
            option("CONFIRMED", "세대 범위를 확인했어요"), option("EXCEPTION", "세대 제외 사유의 입증이 필요해요", true)));
        questions.add(question("housing", "어떤 주택 소유 기준을 확인해야 하나요?",
            "이번 모집은 무주택 요건 완화가 있습니다. 소형·저가 특례는 면적과 법령상 가격 조건을 모두 충족해야 하며, 아파트/그 외와 수도권 여부에 따라 기준이 다릅니다. 다른 지역의 주택 특례는 주택건설지역·연접지역의 소유 여부를 확인하는 기준이며 거주지 순위가 아닙니다. 법정 예외, 취득 시점과 재계약 기준은 원문을 별도로 확인하세요.", sections.evidence("무주택요건", "주택및분양권등을소유한것으로보지"),
            option("NONE", "세대원 모두 주택·분양권이 없어요"), option("SMALL", "소형·저가주택 특례를 확인해야 해요", true),
            option("OUTSIDE", "건설·연접지역 외 주택 특례를 확인해야 해요", true), option("EXCEPTION", "법정 예외 또는 다른 사유를 확인해야 해요", true)));
        questions.add(question("vehicle", "세대 전체의 자동차 기준을 확인했나요?",
            "자동차 기준은 완화하지 않습니다. 비영업용 승용자동차별 가액 중 최고 가액이 " + amount + "만원 이하인지 확인합니다. 총자산용 차량 합산액과 다릅니다. 장애인사용 자동차·해당 국가유공자 보철용 차량의 제외와 저공해차 정부·지자체 보조금 차감을 적용하세요. 배우자 등 다른 세대원의 차량도 포함합니다.", sections.evidence("개별자동차가액", "비영업용승용자동차"),
            option("NO_VEHICLE", "대상 세대에 기준 적용 차량이 없어요"), option("WITHIN", "대상·제외·차감 후 한도 이내임을 확인했어요"),
            option("EXCEPTION", "차량 대상이나 예외를 더 확인해야 해요", true), option("OVER", "한도를 초과해 추가 확인이 필요해요", true)));
        questions.add(question("sublease", "세대원 중 불법양도·전대 적발 후 4년이 지나지 않은 사람이 있나요?", "신청자만이 아니라 공고의 자격검증 대상 세대원을 기준으로 확인하세요.", sections.evidence("불법전대자입주자격제한"),
            option("NO", "없어요"), option("YES", "있어요", true)));
        questions.add(question("duplicate", "중복 신청과 기존 공공임대 입주 기준을 확인했나요?",
            "1세대 1주택 신청 원칙, 중복 신청 무효와 기존 예비입주자 지위를 구분하세요. 기존 임차인의 배우자는 세대 분리만으로 중복 입주가 해소되지 않습니다. 입주 전 기존 주택 명도, 계약·재계약 조건은 원문에서 확인하세요. 이번 선정은 거주지역·순위·배점과 무관한 접수 순번이며 자격 검증은 별도로 진행합니다.", sections.evidence("1세대1주택", "선정방법"),
            option("CONFIRMED", "해당 기준을 확인했어요"), option("REVIEW", "기존 입주·신청 기록을 더 확인해야 해요", true)));
        questions.add(question("engaged", "예비신혼부부로 신청하나요?", "혼인으로 구성할 세대와 대표 신청자를 확인하세요. 대표 신청자는 신청 후 변경할 수 없습니다.", sections.evidence("예비신혼부부의경우"), option("YES", "예"), option("NO", "아니요")));
        questions.add(question("separate-spouse", "신청자와 배우자의 주민등록표가 분리되어 있나요?", "분리 배우자의 전부표기 등본 제출 대상인지 확인합니다.", sections.evidence("분리된신청자의배우자"), option("YES", "예"), option("NO", "아니요 또는 배우자가 없어요")));
        questions.add(question("separate-head", "공고일 이후 당시 세대주와 등본이 분리되었나요?", "공고일 당시 세대주의 주민등록표등본을 추가로 제출하는 경우를 확인합니다.", sections.evidence("현재의세대주"), option("YES", "예"), option("NO", "아니요")));
        questions.add(question("family-certificate", "가족관계증명서의 제출 대상과 생략 조건을 확인했나요?",
            "등본에서 배우자가 확인되면 생략할 수 있으나, 등본상 미성년 자녀가 확인되지 않거나 예비신혼 예정 세대의 추가 가족관계를 증명해야 하는 등 별도 필수 사유가 있습니다. 이혼·재혼 자녀의 인정 범위도 함께 확인하세요. 가산점 관련 서류의 이번 선착순 모집 적용 여부는 검토 항목으로 남깁니다.", sections.evidence("가족관계증명서"),
            option("REQUIRED", "제출 사유가 있어요"), option("EXEMPT", "생략 조건을 확인했고 별도 필수 사유가 없어요"), option("REVIEW", "가산점 등 적용 여부를 확인해야 해요", true)));
        questions.add(question("foreign-spouse", "배우자의 외국인 등록 상태를 확인했나요?",
            "외국인 배우자는 가족관계등록과 외국인등록·국내거소신고 등 공고의 조건을 확인해야 합니다. 국내 미거주 재외국민 또는 미등록 배우자 관련 제한도 원문을 확인하세요. 서류 제출 대상과 신청 가능 여부는 별개입니다.", eligibility,
            option("NONE", "외국인 배우자가 없어요"), option("REGISTERED", "외국인 배우자의 등록·신고 조건을 확인했어요"), option("UNREGISTERED", "등록·거주 조건을 더 확인해야 해요", true)));
        questions.add(question("visit", "현장 신청은 누가 하나요?",
            "본인 방문은 신분증과 도장(서명 대체 가능), 배우자 대리는 양측 신분증·신청자 도장·관계 입증자료를 지참합니다. 기타 대리는 인감방식 또는 본인서명방식 중 한 방식을 사용하며 양측 신분증이 필요합니다. 계약 시의 서류 목록과 구분하세요.", sections.evidence("현장방문신청자구비"),
            option("SELF", "본인이 직접 신청해요"), option("SPOUSE", "배우자가 대신 신청해요"), option("SEAL", "기타 대리인이 인감방식으로 신청해요"), option("SIGNATURE", "기타 대리인이 본인서명방식으로 신청해요")));
        questions.add(question("passport", "신분증으로 2020년 12월 21일 이후 발급한 여권을 사용하나요?", "해당 여권을 사용할 때는 여권정보증명서도 필요합니다. 다른 신분증을 사용하면 이 추가 서류의 대상이 아닙니다.", sections.evidence("2020.12.21"), option("YES", "예"), option("NO", "아니요")));

        add(documents, sections, "application", "국민임대주택 공급신청서", null, List.of(), "공급신청서", "현장 접수 시 공고 첨부 양식으로 제출하세요.");
        add(documents, sections, "privacy", "개인정보 수집·이용 및 제3자 제공 동의서", null, List.of(), "개인정보수집", "대상 세대원 전원의 서명이 필요합니다. 14세 미만은 법정대리인이 서명합니다.");
        add(documents, sections, "waiver", "입주자격 완화 입주자 확인서", null, List.of(), "입주자확인서", "공고의 확인서 제출 지시를 확인하세요. 첨부 확약서와 명칭·공고일의 대응은 검토가 필요합니다.");
        add(documents, sections, "duplicate", "예비입주자 중복선정 불가사항 확인서", null, List.of(), "중복선정", "내용을 확인하고 신청자가 서명 또는 날인하세요.");
        add(documents, sections, "registry", "신청자 주민등록표등본(전부표기)", null, List.of(), "주민등록표등본", "공고일 이후 발급분과 전부표기 기준을 확인하세요. 원본은 공식 접수처에 제출하며 앱에는 올리지 않습니다.");
        add(documents, sections, "finance", "금융정보 등 제공 동의서", null, List.of(), "금융정보", "소득·총자산 요건 배제와 금융정보 조회 동의는 별개입니다. 원문의 세대원 서명·제출 지시를 확인하세요.");
        add(documents, sections, "minor-proof", "미성년 신청 예외 입증자료", "adult", List.of("MINOR_CHILD", "MINOR_SIBLING", "MINOR_PARENT"), "미성년", "선택한 예외의 동일 세대·부양·신고 등 요건에 맞는 자료를 확인하세요.");
        add(documents, sections, "household-proof", "자격검증 세대 제외 입증자료", "household", List.of("EXCEPTION"), "입증서류", "실종·행방불명 등 실제 제외 사유에 맞는 입증자료를 확인하세요.");
        add(documents, sections, "fiance-registry", "예비배우자 주민등록표등본", "engaged", List.of("YES"), "예비신혼부부", "신청자와 예비배우자 모두 각각 제출하는 등본 기준을 확인하세요.");
        add(documents, sections, "fiance-forms", "예비신혼 신청·세대구성확인서와 예비배우자 신분증 사본", "engaged", List.of("YES"), "혼인관계서류", "공고 첨부 양식과 대표 신청자·예정 세대 기준을 확인하세요.");
        add(documents, sections, "spouse-registry", "분리배우자 주민등록표등본", "separate-spouse", List.of("YES"), "분리된신청자의배우자", "배우자 세대의 전부표기 등본을 준비하세요.");
        add(documents, sections, "head-registry", "공고일 당시 세대주의 주민등록표등본", "separate-head", List.of("YES"), "현재의세대주", "공고일 이후 등본 분리 시 추가 제출하는 등본입니다.");
        add(documents, sections, "family", "가족관계증명서(상세)", "family-certificate", List.of("REQUIRED", "REVIEW"), "가족관계증명서", "제출 대상·생략 조건과 누구의 증명서가 필요한지 확인하세요. 적용관계 미확인은 완료로 처리하지 않습니다.");
        add(documents, sections, "foreign", "외국인 배우자 등록증 사본·외국인등록사실증명서", "foreign-spouse", List.of("REGISTERED", "UNREGISTERED"), "외국인등록사실증명서", "등록 상태와 공고의 제출 대상을 확인하세요.");
        add(documents, sections, "self-visit", "본인 신청 신분증·도장 또는 서명 준비", "visit", List.of("SELF"), "본인직접신청", "원문의 인정 신분증을 지참하세요. 본인 신청은 도장을 서명으로 대체할 수 있습니다.");
        add(documents, sections, "spouse-visit", "배우자 대리 신청 준비물", "visit", List.of("SPOUSE"), "배우자가대리신청", "신청자·배우자 신분증, 신청자 도장, 관계 입증자료를 지참하세요.");
        add(documents, sections, "seal-visit", "기타 대리 인감방식 준비물", "visit", List.of("SEAL"), "인감증명방식", "양측 신분증과 신청자의 인감증명서·인감도장·인감날인 위임장이 필요합니다.");
        add(documents, sections, "signature-visit", "기타 대리 본인서명방식 준비물", "visit", List.of("SIGNATURE"), "본인서명방식", "양측 신분증과 본인서명사실확인서·자필서명 위임장이 필요합니다. 수임인 성명을 확인하세요.");
        add(documents, sections, "passport-proof", "여권정보증명서", "passport", List.of("YES"), "2020.12.21", "해당 발급 시점의 여권을 신분증으로 사용할 때 함께 지참하세요.");
        var findings = NationalRentalScopeReview.inspect(source, draft.checkedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString());
        return new Result(new Content("소득·총자산을 배제하는 선착순 국민임대 모집의 검토용 준비 안내입니다. 최초 신청과 재계약 기준을 구분하며, 확인되지 않은 서류 적용관계는 별도 검토 항목으로 남깁니다.",
            original.evidence(), original.attachments(), List.copyOf(questions), List.copyOf(documents), original.schedules()), findings);
    }

    private static Question question(String key, String title, String reason, Evidence evidence, AnswerOption... choices) {
        var options = new ArrayList<>(List.of(choices));
        options.add(option("UNKNOWN", "잘 모르겠어요", true));
        return new Question("nr-" + key, title, reason, evidence, List.copyOf(options));
    }
    private static AnswerOption option(String value, String label) { return option(value, label, false); }
    private static AnswerOption option(String value, String label, boolean followUp) { return new AnswerOption(value, label, followUp); }
    private static void add(List<DocumentTemplate> documents, NationalRentalSections source, String key, String title,
                            String question, List<String> values, String term, String method) {
        documents.add(new DocumentTemplate("nr-doc-" + key, title, question == null ? null : "nr-" + question,
            method, "공고문 발급처·제출 기준 확인", null, source.evidence(term), question == null ? null : values));
    }
}
