package org.jibro.collection.review.followup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.*;
import org.jibro.notice.OfficialCorrectionLink;
import org.jibro.notice.SupplementalSourceVerifier;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.collection.review.followup.OfficialFollowupModels.*;

/**
 * Bounded, read-only discovery. A complete search is not an approval or a personal eligibility decision.
 */
@Component
public final class OfficialFollowupDiscovery {
    private static final int MAX_PAGES = 2, MAX_CANDIDATES = 12, MAX_RELATED = 6, MAX_FILES = 8;
    private static final Duration START_BUDGET = Duration.ofSeconds(120);
    private final AttachmentFingerprintClient http;
    private final NoticeExtractionService extraction;
    private final Clock clock;
    private final AgencyIntakeClient agencies;

    @org.springframework.beans.factory.annotation.Autowired
    public OfficialFollowupDiscovery(AttachmentFingerprintClient http, NoticeExtractionService extraction, AgencyIntakeClient agencies) {
        this(http, extraction, Clock.systemUTC(), agencies);
    }

    OfficialFollowupDiscovery(AttachmentFingerprintClient http, NoticeExtractionService extraction, Clock clock, AgencyIntakeClient agencies) {
        this.agencies = agencies;
        this.http = http;
        this.extraction = extraction;
        this.clock = clock;
    }

    public Discovery discover(Target target, Draft primary) {
        return discover(target, primary, false);
    }

    Discovery discover(Target target, Draft primary, boolean retainAlternatives) {
        var lh = discoverLh(target, primary);
        if (target == null || !target.activePreparation() || primary == null || target.publishedDate() == null
            || lh.blockers().stream().anyMatch(b -> b.startsWith("FOLLOWUP_PRIMARY_"))) return lh;
        if (!lh.complete() && !retainAlternatives && primary.sources().stream().noneMatch(OfficialFollowupDiscovery::unread)
            && lh.related().stream().flatMap(r -> r.sources().stream()).noneMatch(OfficialFollowupDiscovery::unread)) return lh;
        var related = new LinkedHashMap<String, Related>();
        lh.related().forEach(r -> related.put(r.url(), r));
        var blockers = new LinkedHashSet<>(lh.blockers());
        try {
            agencies.preparation(target, primary.sources()).forEach(r -> related.putIfAbsent(r.url(), r));
        } catch (java.io.IOException failure) {
            blockers.add("AGENCY_PREPARATION_RETRY");
        }
        if (retainAlternatives || blockers.contains("AGENCY_PREPARATION_RETRY") || primary.sources().stream().anyMatch(OfficialFollowupDiscovery::unread)
            || related.values().stream().flatMap(r -> r.sources().stream()).anyMatch(OfficialFollowupDiscovery::unread)) {
            try {
                var current = target;
                var sources = primary.sources();
                for (var correction : lh.related()) {
                    if (!"CORRECTION".equals(correction.kind())) continue;
                    current = new Target(target.noticeId(), target.revision(), correction.url(), correction.title(), correction.publishedDate());
                    sources = correction.sources();
                }
                agencies.alternatives(current, sources, blockers).forEach(r -> related.putIfAbsent(r.url(), r));
            } catch (java.io.IOException failure) {
                blockers.add("OFFICIAL_ALTERNATIVE_RETRY");
            }
        }
        if (related.size() > 10) return new Discovery(lh.checkedAt(), lh.related(), List.of("AGENCY_PREPARATION_LIMIT"));
        return new Discovery(lh.checkedAt(), List.copyOf(related.values()), List.copyOf(blockers));
    }

    private static boolean unread(Source source) {
        return !"EXTRACTED".equals(source.status()) || OcrProvenance.contains(source)
            || source.context().isEmpty() || source.context().stream().anyMatch(p -> p.text().contains("[발췌 길이 제한]"));
    }

    private Discovery discoverLh(Target target, Draft primary) {
        Instant checked = clock.instant();
        var related = new ArrayList<Related>();
        var blockers = new LinkedHashSet<String>();
        var budget = new Budget();
        if (target == null || primary == null || target.noticeId() == null || target.revision() < 1
            || !target.noticeId().toString().equals(primary.noticeId()) || target.revision() != primary.revision()
            || target.title() == null || target.title().isBlank() || primary.checkedAt() == null
            || !Set.of("READY", "PARTIAL", "FAILED").contains(primary.status()))
            return new Discovery(checked, List.of(), List.of("FOLLOWUP_PRIMARY_SNAPSHOT_INVALID"));
        String primaryHtml;
        try {
            OfficialCorrectionLink.identity(target.primaryUrl());
            budget.take();
            primaryHtml = http.html(target.primaryUrl());
            var fresh = OfficialDetailParser.parse(primaryHtml, target.primaryUrl());
            var stored = primary.sources().stream().filter(source -> source.url().equals(target.primaryUrl())).toList();
            if (stored.size() != 1 || !fresh.parts().equals(stored.get(0).context()))
                return new Discovery(checked, List.of(), List.of("FOLLOWUP_PRIMARY_CHANGED"));
        } catch (Exception failure) {
            interrupted(failure);
            return new Discovery(checked, List.of(), List.of("FOLLOWUP_PRIMARY_UNREAD"));
        }
        try {
            String previousUrl = target.primaryUrl(), previousHtml = primaryHtml;
            var seen = new HashSet<OfficialCorrectionLink.Identity>();
            seen.add(OfficialCorrectionLink.identity(previousUrl));
            while (true) {
                var candidate = OfficialFollowupPageParser.correctionCandidate(previousUrl, previousHtml);
                if (candidate.isEmpty()) break;
                if (related.size() >= MAX_RELATED || !seen.add(OfficialCorrectionLink.identity(candidate.get()))) {
                    blockers.add("FOLLOWUP_CORRECTION_CHAIN_UNVERIFIED");
                    break;
                }
                budget.take();
                String html = http.html(candidate.get());
                OfficialCorrectionLink.Proof proof;
                try {
                    proof = OfficialCorrectionLink.verify(previousUrl, previousHtml, candidate.get(), html);
                } catch (IllegalArgumentException noDirectProof) {
                    budget.take();
                    String history = http.html(OfficialCorrectionLink.historyUrl(candidate.get()));
                    proof = OfficialCorrectionLink.verifyFamilyHistory(previousUrl, previousHtml, candidate.get(), html, history);
                }
                var date = OfficialFollowupPageParser.correctionNoticeDate(html);
                var correction = readRelated("CORRECTION", candidate.get(), html, date,
                    (proof.historyHtmlSha256() == null ? "공식 정정 링크 양방향 공고 ID " : "공식 정정 이력과 양쪽 상세의 공고 ID ")
                        + proof.originalPanId() + " → " + proof.correctedPanId()
                        + " 및 정정사유: " + proof.reason() + " · 정정본 공고일 " + date + " (게시일 추정 아님)", budget, blockers);
                related.add(new Related(correction.kind(), correction.url(), correction.title(), correction.publishedDate(),
                    correction.relationshipProof(), correction.sources(), previousUrl));
                previousUrl = candidate.get();
                previousHtml = html;
            }
        } catch (BudgetExceeded exhausted) {
            blockers.add("FOLLOWUP_WORK_BUDGET_EXCEEDED");
        } catch (Exception failure) {
            interrupted(failure);
            blockers.add("FOLLOWUP_CORRECTION_UNVERIFIED");
        }

        var query = OfficialFollowupRelationship.queryTerm(target);
        if (query.isEmpty() || target.publishedDate() == null || target.publishedDate().isAfter(LocalDate.now(clock))) {
            blockers.add("FOLLOWUP_SEARCH_IDENTITY_INCOMPLETE");
            return result(checked, related, blockers);
        }
        var rows = new LinkedHashMap<String, OfficialFollowupPageParser.Row>();
        try {
            int total = -1;
            for (int page = 1; page <= MAX_PAGES; page++) {
                budget.take();
                var parsed = OfficialFollowupPageParser.search(http.html(searchUrl(query.get(), target.publishedDate(), LocalDate.now(clock), page)), page);
                if (total >= 0 && total != parsed.total()) blockers.add("FOLLOWUP_SEARCH_CHANGED_DURING_READ");
                total = parsed.total();
                for (var row : parsed.rows()) {
                    if (rows.putIfAbsent(row.url(), row) != null) blockers.add("FOLLOWUP_SEARCH_PAGE_OVERLAP");
                }
                if (page >= parsed.pages()) break;
                if (page == MAX_PAGES) blockers.add("FOLLOWUP_SEARCH_PAGE_LIMIT");
            }
            if (total >= 0 && rows.size() != total) blockers.add("FOLLOWUP_SEARCH_INCOMPLETE");
        } catch (BudgetExceeded exhausted) {
            blockers.add("FOLLOWUP_WORK_BUDGET_EXCEEDED");
        } catch (Exception failure) {
            interrupted(failure);
            blockers.add("FOLLOWUP_SEARCH_UNREAD");
        }

        int examined = 0;
        var family = new HashMap<OfficialCorrectionLink.Identity, String>();
        family.put(OfficialCorrectionLink.identity(target.primaryUrl()), target.primaryUrl());
        related.stream().filter(item -> item.kind().equals("CORRECTION"))
            .forEach(item -> family.put(OfficialCorrectionLink.identity(item.url()), item.url()));
        var orderedRows = rows.values().stream().sorted(Comparator
            .comparingInt((OfficialFollowupPageParser.Row row) ->
                OfficialFollowupRelationship.titleReferencesTargetDate(target, row.title()) ? 0 : 1)
            .thenComparing(OfficialFollowupPageParser.Row::publishedDate, Comparator.reverseOrder())
            .thenComparing(OfficialFollowupPageParser.Row::url)).toList();
        for (var row : orderedRows) {
            // Search covers title + body: a generic title may contain an exact primary link in its body.
            if (row.publishedDate().isBefore(target.publishedDate())) continue;
            if (examined++ >= MAX_CANDIDATES) {
                blockers.add("FOLLOWUP_CANDIDATE_LIMIT");
                break;
            }
            try {
                budget.take();
                String html = http.html(row.url());
                var page = SupplementalSourceVerifier.parse(row.url(), html);
                LocalDate published = OfficialFollowupPageParser.publication(html);
                if (!published.equals(row.publishedDate()) || !page.title().equals(row.title())) {
                    blockers.add("FOLLOWUP_LIST_DETAIL_CHANGED");
                    continue;
                }
                var context = OfficialFollowupPageParser.context(html, row.url());
                var links = OfficialFollowupPageParser.primaryLinks(html, row.url());
                if (!links.isEmpty() && !family.keySet().containsAll(links)) {
                    // A textual match cannot override an explicit link to another recruitment.
                    var match = OfficialFollowupRelationship.match(target, context);
                    if (links.stream().anyMatch(family::containsKey) || match.proof() != null || match.ambiguous())
                        blockers.add("FOLLOWUP_RELATION_UNVERIFIED");
                    continue;
                }
                if (OfficialFollowupRelationship.multipleRecruitments(target, context)) {
                    blockers.add("FOLLOWUP_RELATION_UNVERIFIED");
                    continue;
                }
                String proof;
                if (!links.isEmpty())
                    proof = "공식 후속 본문의 링크가 원공고 또는 검증된 정정 공고 ID·시스템·공급유형과 일치: "
                        + String.join(", ", links.stream().map(family::get).sorted().toList());
                else {
                    var match = OfficialFollowupRelationship.match(target, context);
                    proof = match.proof();
                    if (proof == null) {
                        if (match.ambiguous()) blockers.add("FOLLOWUP_RELATION_UNVERIFIED");
                        continue;
                    }
                }
                if (related.size() >= MAX_RELATED) {
                    blockers.add("FOLLOWUP_RELATED_LIMIT");
                    break;
                }
                related.add(readRelated("FOLLOWUP", row.url(), html, published, proof, budget, blockers));
            } catch (BudgetExceeded exhausted) {
                blockers.add("FOLLOWUP_WORK_BUDGET_EXCEEDED");
                break;
            } catch (Exception failure) {
                interrupted(failure);
                blockers.add("FOLLOWUP_CANDIDATE_UNREAD");
            }
        }
        return result(checked, related, blockers);
    }

    /**
     * Publication must reject a changed dependency set and compile against a newly saved discovery.
     */
    public Discovery revalidate(Target target, Draft primary, Discovery previous) {
        var fresh = discover(target, primary, previous != null && previous.hasMirror());
        if (previous == null || !fingerprint(previous).equals(fingerprint(fresh))) {
            var blockers = new LinkedHashSet<>(fresh.blockers());
            blockers.add("FOLLOWUP_EVIDENCE_CHANGED");
            return new Discovery(fresh.checkedAt(), fresh.related(), List.copyOf(blockers));
        }
        return fresh;
    }

    public static String fingerprint(Discovery value) {
        try {
            var stable = new Discovery(null, value.related(), value.blockers());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(stable)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Related readRelated(String kind, String url, String html, LocalDate published, String proof,
                                Budget budget, Set<String> blockers) {
        var parsed = SupplementalSourceVerifier.parse(url, html);
        List<Part> parts = kind.equals("CORRECTION") ? OfficialDetailParser.parse(html, url).parts()
            : OfficialFollowupPageParser.context(html, url);
        var sources = new ArrayList<Source>();
        sources.add(source(parsed.title(), url, parsed.sha256(), parts));
        var labels = new HashMap<String, String>();
        OfficialDetailParser.parse(html, url).attachments().forEach(file -> labels.put(file.url(), file.label()));
        for (String attachment : parsed.attachmentUrls()) {
            String label = labels.getOrDefault(attachment, "공식 후속 첨부");
            if (budget.files >= MAX_FILES) {
                sources.add(new Source(label, attachment, null, "DEFERRED", "이번 발췌 한도 이후의 첨부입니다.", List.of()));
                blockers.add("FOLLOWUP_ATTACHMENT_LIMIT");
                continue;
            }
            budget.take();
            budget.files++;
            Source value = label.toLowerCase(Locale.ROOT).matches(".*\\.html?\\s*$")
                ? htmlAttachment(label, attachment, blockers) : extraction.extract(new Link(label, attachment));
            sources.add(value);
            if (!"EXTRACTED".equals(value.status())) blockers.add("FOLLOWUP_ATTACHMENT_UNREAD");
            if (OcrProvenance.contains(value)) blockers.add("FOLLOWUP_OCR_REQUIRES_SOURCE_REVIEW");
        }
        return new Related(kind, url, parsed.title(), published, proof, sources);
    }

    private Source htmlAttachment(String label, String url, Set<String> blockers) {
        try {
            var download = http.htmlAttachmentForSourceReview(url);
            String html = new String(download.bytes(), StandardCharsets.UTF_8);
            if (!html.toLowerCase(Locale.ROOT).contains("<html")) throw new IllegalArgumentException();
            var doc = Jsoup.parse(html);
            doc.select("script,style,noscript").remove();
            var parsed = OfficialDetailParser.parse(doc.outerHtml(), url);
            if (!parsed.attachments().isEmpty()) blockers.add("FOLLOWUP_HTML_NESTED_ATTACHMENT_UNREAD");
            return source(label, url, download.fingerprint().sha256(), parsed.parts());
        } catch (Exception failure) {
            interrupted(failure);
            return new Source(label, url, null, "FAILED", "공식 HTML 첨부를 읽지 못했습니다.", List.of());
        }
    }

    private static Source source(String label, String url, String sha, List<Part> parts) {
        if (parts.size() > 1000 || parts.stream().mapToLong(part -> part.text().length()).sum() > 1_000_000
            || parts.stream().anyMatch(part -> part.text().indexOf('\0') >= 0))
            throw new IllegalArgumentException("FOLLOWUP_TEXT_LIMIT");
        boolean read = parts.stream().anyMatch(part -> !part.text().isBlank());
        return new Source(label, url, sha, read ? "EXTRACTED" : "EMPTY", "공식 추가 자료 발췌 · 모집 연결과 변경 범위 별도 검증",
            ExcerptClassifier.extract(parts, url), parts);
    }

    private static Discovery result(Instant checked, List<Related> related, Set<String> blockers) {
        return new Discovery(checked, related.stream().sorted(Comparator.comparing(Related::url)).toList(),
            blockers.stream().sorted().toList());
    }

    static String searchUrl(String term, LocalDate start, LocalDate end, int page) {
        return "https://apply.lh.or.kr/lhapply/apply/noti/an/list.do?slBbsKdCd=03&mi=1079&srchTy=0&srchNm="
            + URLEncoder.encode(term, StandardCharsets.UTF_8) + "&startDt=" + start + "&endDt=" + end
            + "&currPage=" + page + "&listCo=100&srchRes=Y&indVal=N";
    }

    private static void interrupted(Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    }

    private static final class Budget {
        final long started = System.nanoTime();
        int requests, files;

        void take() {
            if (Thread.currentThread().isInterrupted() || requests++ >= 25 || System.nanoTime() - started > START_BUDGET.toNanos())
                throw new BudgetExceeded();
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
    }
}
