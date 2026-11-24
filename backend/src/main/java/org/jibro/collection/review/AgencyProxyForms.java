package org.jibro.collection.review;

import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.*;

/**
 * Compare the instructions printed on the actual mandate with the recruitment's proxy submission table.
 */
final class AgencyProxyForms {
    private AgencyProxyForms() {
    }

    static GuidanceReviewCompiler.Assessment apply(GuidanceReviewCompiler.Assessment assessment, List<Source> primary,
                                                   List<Source> agency, String checked) {
        var guide = assessment.candidate();
        if (!assessment.ready() || guide == null || agency.isEmpty()) return assessment;
        var mandates = guide.documents().stream().filter(doc -> doc.title().contains("위임장")
                || doc.title().contains("대리") && doc.method().contains("위임장")).toList();
        if (mandates.isEmpty()) return assessment;
        Evidence signature = null, proxySeal = null, personalSeal = null;
        boolean linkedForm = false;
        for (var source : java.util.stream.Stream.concat(primary.stream(), agency.stream()).toList()) {
            if (!"EXTRACTED".equals(source.status()) || org.jibro.collection.extraction.OcrProvenance.contains(source))
                continue;
            for (var part : source.context()) {
                String text = part.text().replaceAll("[\\s\\p{Zs}]+", "");
                if (!text.contains("위임장") && !source.label().contains("위임장") && !part.location().contains("위임장"))
                    continue;
                var evidence = new Evidence(source.label() + " · " + part.location(), EvidenceLocationUrl.forSource(source, part.location(), "위임장"), checked);
                if (text.contains("본인서명사실확인서상의서명일것") && text.contains("수임인서명란에대리인성명을반드시명기")) {
                    signature = evidence;
                    if (agency.contains(source)) linkedForm = true;
                }
                if (text.contains("대리발급한신청자의인감증명서로신청은가능")) {
                    proxySeal = evidence;
                    if (agency.contains(source)) linkedForm = true;
                }
                if (text.matches("(?s).*(?:위임인인감증명서(?:1통)?[(（]본인발급분[)）]|인감증명서(?:1통)?[(（]위임인[-·]?본인발급분[)）]).*")) {
                    personalSeal = evidence;
                    if (agency.contains(source)) linkedForm = true;
                }
            }
        }
        if (!linkedForm || signature == null && (proxySeal == null || personalSeal == null)) return assessment;
        var docs = new ArrayList<>(guide.documents());
        var issues = new ArrayList<>(guide.reviewIssues());
        if (signature != null) {
            for (var doc : mandates) {
                String method = doc.method() + " 본인서명 방식을 쓰면 신청자가 본인서명사실확인서와 같은 서명을 하고 수임인란에 대리인 이름을 적으세요.";
                if (method.length() <= 2000)
                    docs.set(docs.indexOf(doc), new DocumentTemplate(doc.id(), doc.title(), doc.conditionQuestionId(), method,
                            doc.issuer(), doc.issueUrl(), doc.evidence(), doc.conditionAnswerValues(), doc.issueLabel()));
            }
        }
        if (proxySeal != null && personalSeal != null) {
            issues.add(new ReviewIssue("agency-proxy-seal-issuer", "DOCUMENT", "대리발급 인감증명서 접수 여부",
                    "본문은 대리발급을 허용하고 위임장 서식은 본인발급분을 요구합니다. 대리발급 증명서를 쓸 때만 접수처에 확인하세요.",
                    List.of(), mandates.stream().map(DocumentTemplate::id).toList(), java.util.stream.Stream.of(proxySeal, personalSeal).distinct().toList()));
        }
        var attachments = new LinkedHashMap<String, Evidence>();
        guide.attachments().forEach(item -> attachments.put(item.url(), item));
        for (var proof : java.util.stream.Stream.of(signature, proxySeal, personalSeal).filter(Objects::nonNull).toList())
            attachments.putIfAbsent(EvidenceLocationUrl.base(proof.url()), proof);
        var result = new Content(guide.summary(), guide.evidence(), List.copyOf(attachments.values()), guide.questions(), docs, guide.schedules(), issues, guide.applicationClosure());
        return new GuidanceReviewCompiler.Assessment(assessment.profile(), assessment.ready(), result, assessment.issues(), assessment.findings(), assessment.startDate(), assessment.endDate());
    }
}
