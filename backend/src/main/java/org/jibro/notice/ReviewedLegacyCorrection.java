package org.jibro.notice;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jibro.collection.attachments.AttachmentFingerprintClient;

/**
 * Explicit local editorial review, never a title-based collection fallback.
 */
public final class ReviewedLegacyCorrection {
    public record Attachment(String originalFileId, String correctedFileId, String sha256, String mediaType) {
    }

    public record Review(String originalUrl, String correctedUrl, String title, String publishedDate,
                         String correctionReason, String findings, List<Attachment> attachments) {
        public Review {
            attachments = List.copyOf(attachments);
        }
    }

    private static final Pattern FILE = Pattern.compile("javascript:fileDownLoad\\('([0-9]{1,20})'\\);?");
    private static final Set<String> TYPES = Set.of("application/pdf", "application/x-ole-storage");

    private ReviewedLegacyCorrection() {
    }

    static OfficialCorrectionLink.Proof verify(Review review, String oldUrl, String oldHtml,
                                               String latestUrl, String latestHtml, AttachmentFingerprintClient http) throws Exception {
        if (review == null || !Objects.equals(review.originalUrl(), oldUrl) || !Objects.equals(review.correctedUrl(), latestUrl)
                || review.findings() == null || review.findings().length() < 20 || review.findings().length() > 2000
                || review.attachments().size() != 2
                || !review.attachments().stream().map(Attachment::mediaType).collect(java.util.stream.Collectors.toSet()).equals(TYPES))
            throw invalid();
        var proof = OfficialCorrectionLink.unlinkedLegacyPages(oldUrl, oldHtml, latestUrl, latestHtml);
        if (!proof.reason().equals(review.correctionReason())) throw invalid();
        LocalDate.parse(review.publishedDate());
        checkPage(oldHtml, review.title(), review.publishedDate());
        checkPage(latestHtml, "[정정공고] " + review.title(), review.publishedDate());
        var seen = new HashSet<String>();
        for (var attachment : review.attachments()) {
            if (attachment.sha256() == null || !attachment.sha256().matches("[0-9a-f]{64}")) throw invalid();
            for (boolean original : List.of(true, false)) {
                String fileId = original ? attachment.originalFileId() : attachment.correctedFileId();
                if (fileId == null || !fileId.matches("[0-9]{1,20}") || !seen.add(fileId)) throw invalid();
                checkAttachment(original ? oldHtml : latestHtml, fileId, attachment.mediaType());
            }
        }
        for (var attachment : review.attachments()) {
            for (String fileId : List.of(attachment.originalFileId(), attachment.correctedFileId())) {
                var fingerprint = http.fetch("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + fileId);
                if (!attachment.sha256().equals(fingerprint.sha256()) || !attachment.mediaType().equals(fingerprint.mediaType())
                        || fingerprint.bytes() < 10000) throw invalid();
            }
        }
        return proof;
    }

    private static void checkPage(String html, String title, String date) {
        var doc = Jsoup.parse(html);
        var titles = doc.select(".bbs_ViewA h3");
        var dates = doc.select(".bbs_ViewA .bbsV_data li").stream()
                .filter(li -> li.select("strong").text().equals("공고일"))
                .map(li -> {
                    var copy = li.clone();
                    copy.select("strong").remove();
                    return copy.text().strip();
                }).toList();
        if (titles.size() != 1 || !titles.text().equals(title) || dates.size() != 1
                || !dates.get(0).equals(date.replace('-', '.'))) throw invalid();
    }

    private static void checkAttachment(String html, String fileId, String type) {
        var matches = Jsoup.parse(html).select(".bbs_ViewA a[href]").stream().filter(a -> {
            var matcher = FILE.matcher(a.attr("href"));
            return matcher.matches() && matcher.group(1).equals(fileId);
        }).toList();
        String extension = type.equals("application/pdf") ? ".pdf" : ".hwp";
        if (matches.size() != 1 || !matches.get(0).text().contains("공고문") || !matches.get(0).text().endsWith(extension))
            throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("REVIEWED_CORRECTION_EVIDENCE_CHANGED");
    }
}
