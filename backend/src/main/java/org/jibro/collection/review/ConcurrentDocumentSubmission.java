package org.jibro.collection.review;

import static org.jibro.collection.review.ScheduleCandidates.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Evidence;

/** Links a general application-time submission instruction to a deadline in the SAME fetched source. */
final class ConcurrentDocumentSubmission {
    private static final Pattern INSTRUCTION = Pattern.compile(
        "신청\\s*시\\s*제출\\s*서류\\s*[:：]\\s*신청서?\\s*접수\\s*시\\s*스캔하여\\s*첨부"
        + "|신청방법\\s*[:：][^\\n]{0,180}신청\\s*\\(서류\\s*스캔\\s*첨부\\)"
        + "|신청방법\\s*[:：]\\s*제출서류\\s*스캔\\s*후\\s*첨부하여[^\\n]{0,180}신청"
        + "|(?:신청|접수)\\s*시\\s*(?:모든\\s*)?(?:제출서류|구비서류|신청서류)를?\\s*(?:함께\\s*)?제출(?:해야|합니다|하여야)"
        + "|신청서류를\\s*구비하지\\s*않고\\s*방문한\\s*경우\\s*접수가\\s*불가능"
        + "|접수기간\\s*동안\\s*신청서류를\\s*지참하신\\s*분들에\\s*한하여");
    private static final Pattern DIFFERENT_SCOPE = Pattern.compile(
        "(?:단지|주택|지역|순위|유형|채널)별[^\\n]{0,60}(?:서류|접수|신청|일정)[^\\n]{0,25}(?:다르|다릅|상이|각각|별도)"
        + "|(?:서류는|제출서류는|신청서류는)[^\\n]{0,25}(?:선정|선발|계약|동호\\s*지정)[^\\n]{0,8}(?:후|당일|시)[^\\n]{0,10}제출"
        + "|(?:서류는|제출서류는|신청서류는)\\s*(?:추후|별도|보완)[^\\n]{0,20}(?:제출|안내)"
        + "|(?:계약|동호\\s*지정)(?:\\s*당일|\\s*시)[^\\n]{0,20}(?:신청|제출)서류"
        + "|제출서류(?:\\s*중)?\\s*일부|제출서류[^\\n]{0,15}(?:면제|제출하지\\s*않|필요\\s*없)");
    private static final Pattern CONDITIONAL_INSTRUCTION = Pattern.compile("고령|장애|대리인|해당자|일부|선정자|선발자|대상자|온라인.*(?:별도|다르)|인터넷.*(?:별도|다르)");

    private ConcurrentDocumentSubmission() {}

    static void add(List<Source> sources, Map<Kind, List<Candidate>> candidates,
                    Map<Kind, List<Unresolved>> unresolved, Set<String> issues, boolean scoped) {
        // An independently dated/unknown document stage takes priority over initial application documents.
        if (scoped || candidates.containsKey(Kind.DOCUMENTS) || unresolved.containsKey(Kind.DOCUMENTS)
            || issues.stream().anyMatch(issue -> issue.endsWith(":DOCUMENTS"))) return;
        if (sources.stream().flatMap(value -> value.context().stream())
            .anyMatch(part -> DIFFERENT_SCOPE.matcher(part.text()).find())) return;
        List<Candidate> derived = new ArrayList<>();
        for (Source source : sources) {
            if (!"EXTRACTED".equals(source.status()) || source.url() == null || source.sha256() == null || source.sha256().isBlank()
                || org.jibro.collection.extraction.OcrProvenance.contains(source)) continue;
            var ends = candidates.getOrDefault(Kind.END, List.of()).stream()
                .filter(end -> source.url().equals(EvidenceLocationUrl.base(end.evidence().url()))).toList();
            if (ends.isEmpty() || ends.stream().map(Candidate::day).distinct().count() != 1
                || ends.stream().map(Candidate::time).filter(java.util.Objects::nonNull).distinct().count() > 1) continue;
            var instruction = source.context().stream().filter(part -> part.location() != null && !part.location().isBlank()
                && part.text().lines().anyMatch(line -> INSTRUCTION.matcher(line).find()
                    && !CONDITIONAL_INSTRUCTION.matcher(line).find())).findFirst();
            if (instruction.isEmpty()) continue;
            Candidate end = ends.stream().filter(value -> value.time() != null).findFirst().orElse(ends.get(0));
            Evidence evidence = new Evidence(end.evidence().label() + " · " + instruction.get().location()
                + " · 신청 시 서류 동시 제출", EvidenceLocationUrl.forSource(source, instruction.get().location(),
                    instruction.get().text().lines().filter(line -> INSTRUCTION.matcher(line).find()).findFirst().orElse("")), end.evidence().checkedAt());
            derived.add(new Candidate(end.day(), end.time(), evidence, ends.stream().anyMatch(Candidate::early)));
        }
        if (!derived.isEmpty()) candidates.put(Kind.DOCUMENTS, derived);
    }
}
