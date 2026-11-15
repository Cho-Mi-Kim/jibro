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

/** Bounded, read-only discovery. A complete search is not an approval or a personal eligibility decision. */
@Component
public final class OfficialFollowupDiscovery {
    private static final int MAX_PAGES = 2, MAX_CANDIDATES = 12, MAX_RELATED = 6, MAX_FILES = 8;
    private static final Duration START_BUDGET = Duration.ofSeconds(120);
    private final AttachmentFingerprintClient http;
    private final NoticeExtractionService extraction;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OfficialFollowupDiscovery(AttachmentFingerprintClient http, NoticeExtractionService extraction) {
        this(http, extraction, Clock.systemUTC());
    }
    OfficialFollowupDiscovery(AttachmentFingerprintClient http, NoticeExtractionService extraction, Clock clock) {
        this.http = http; this.extraction = extraction; this.clock = clock;
    }

    public Discovery discover(Target target, Draft primary) {
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
            budget.take(); primaryHtml = http.html(target.primaryUrl());
            var fresh = OfficialDetailParser.parse(primaryHtml, target.primaryUrl());
            var stored = primary.sources().stream().filter(source -> source.url().equals(target.primaryUrl())).toList();
            if (stored.size() != 1 || !fresh.parts().equals(stored.get(0).context()))
                return new Discovery(checked, List.of(), List.of("FOLLOWUP_PRIMARY_CHANGED"));
        } catch (Exception failure) {
            interrupted(failure);
            return new Discovery(checked, List.of(), List.of("FOLLOWUP_PRIMARY_UNREAD"));
        }
        try {
            var candidate = OfficialFollowupPageParser.correctionCandidate(target.primaryUrl(), primaryHtml);
            if (candidate.isPresent()) {
                budget.take(); String html = http.html(candidate.get());
                var proof = OfficialCorrectionLink.verify(target.primaryUrl(), primaryHtml, candidate.get(), html);
                var date = OfficialFollowupPageParser.correctionNoticeDate(html);
                related.add(readRelated("CORRECTION", candidate.get(), html, date,
                    "공식 정정 링크 양방향 공고 ID " + proof.originalPanId() + " → " + proof.correctedPanId()
                        + " 및 정정사유: " + proof.reason() + " · 정정본 공고일 " + date + " (게시일 추정 아님)", budget, blockers));
            }
        } catch (BudgetExceeded exhausted) { blockers.add("FOLLOWUP_WORK_BUDGET_EXCEEDED"); }
        catch (Exception failure) { interrupted(failure); blockers.add("FOLLOWUP_CORRECTION_UNVERIFIED"); }

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
        } catch (BudgetExceeded exhausted) { blockers.add("FOLLOWUP_WORK_BUDGET_EXCEEDED"); }
        catch (Exception failure) { interrupted(failure); blockers.add("FOLLOWUP_SEARCH_UNREAD"); }

        int examined = 0;
        var orderedRows = rows.values().stream().sorted(Comparator
            .comparingInt((OfficialFollowupPageParser.Row row) ->
                OfficialFollowupRelationship.titleReferencesTargetDate(target, row.title()) ? 0 : 1)
            .thenComparing(OfficialFollowupPageParser.Row::publishedDate, Comparator.reverseOrder())
            .thenComparing(OfficialFollowupPageParser.Row::url)).toList();
        for (var row : orderedRows) {
            // Search covers title + body: a generic title may contain an exact primary link in its body.
            if (row.publishedDate().isBefore(target.publishedDate())) continue;
            if (examined++ >= MAX_CANDIDATES) { blockers.add("FOLLOWUP_CANDIDATE_LIMIT"); break; }
            try {
                budget.take(); String html = http.html(row.url());
                var page = SupplementalSourceVerifier.parse(row.url(), html);
                LocalDate published = OfficialFollowupPageParser.publication(html);
                if (!published.equals(row.publishedDate()) || !page.title().equals(row.title())) {
                    blockers.add("FOLLOWUP_LIST_DETAIL_CHANGED"); continue;
                }
                var context = OfficialFollowupPageParser.context(html, row.url());
                if (OfficialFollowupPageParser.primaryLinks(html, row.url()).size() > 1
                    || OfficialFollowupRelationship.multipleRecruitments(target, context)) {
                    blockers.add("FOLLOWUP_RELATION_UNVERIFIED"); continue;
                }
                String proof;
                if (OfficialFollowupPageParser.linksPrimary(target.primaryUrl(), html, row.url()))
                    proof = "공식 후속 본문 .bbsV_cont의 링크가 원모집 공고 ID·시스템·공급유형과 일치: " + target.primaryUrl();
                else {
                    var match = OfficialFollowupRelationship.match(target, context);
                    proof = match.proof();
                    if (proof == null) {
                        if (match.ambiguous()) blockers.add("FOLLOWUP_RELATION_UNVERIFIED");
                        continue;
                    }
                }
                if (related.size() >= MAX_RELATED) { blockers.add("FOLLOWUP_RELATED_LIMIT"); break; }
                related.add(readRelated("FOLLOWUP", row.url(), html, published, proof, budget, blockers));
            } catch (BudgetExceeded exhausted) { blockers.add("FOLLOWUP_WORK_BUDGET_EXCEEDED"); break; }
            catch (Exception failure) { interrupted(failure); blockers.add("FOLLOWUP_CANDIDATE_UNREAD"); }
        }
        return result(checked, related, blockers);
    }

    /** Publication must reject a changed dependency set and compile against a newly saved discovery. */
    public Discovery revalidate(Target target, Draft primary, Discovery previous) {
        var fresh = discover(target, primary);
        if (previous == null || !fingerprint(previous).equals(fingerprint(fresh))) {
            var blockers = new LinkedHashSet<>(fresh.blockers()); blockers.add("FOLLOWUP_EVIDENCE_CHANGED");
            return new Discovery(fresh.checkedAt(), fresh.related(), List.copyOf(blockers));
        }
        return fresh;
    }

    public static String fingerprint(Discovery value) {
        try {
            var stable = new Discovery(null, value.related(), value.blockers());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(stable)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
                blockers.add("FOLLOWUP_ATTACHMENT_LIMIT"); continue;
            }
            budget.take(); budget.files++;
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
            var doc = Jsoup.parse(html); doc.select("script,style,noscript").remove();
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
            || parts.stream().anyMatch(part -> part.text().indexOf('\0') >= 0)) throw new IllegalArgumentException("FOLLOWUP_TEXT_LIMIT");
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
    private static void interrupted(Exception failure) { if (failure instanceof InterruptedException) Thread.currentThread().interrupt(); }
    private static final class Budget {
        final long started = System.nanoTime(); int requests, files;
        void take() {
            if (Thread.currentThread().isInterrupted() || requests++ >= 25 || System.nanoTime() - started > START_BUDGET.toNanos())
                throw new BudgetExceeded();
        }
    }
    private static final class BudgetExceeded extends RuntimeException {}
}
