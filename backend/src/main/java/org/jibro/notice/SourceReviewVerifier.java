package org.jibro.notice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;

import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.SourceReviewModels.*;

/**
 * Re-fetches reviewed evidence before an explicitly requested editorial application. No scheduler uses this path.
 */
@Component
public class SourceReviewVerifier {
    private final AutomaticReviewRepository extractions;
    private final NoticeService notices;
    private final AttachmentFingerprintClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final SupplementalSourceVerifier supplemental;

    /**
     * Cannot be constructed by a caller; only successful live verification issues a receipt.
     */
    public static final class Verification {
        private final String packageFingerprint, extractionFingerprint, detailUrl;
        private final Instant checkedAt;
        private final Map<String, String> freshDigests;
        private final boolean conditionsOnly;
        private final Set<String> retainedEvidence;

        private Verification(String packageFingerprint, String extractionFingerprint, String detailUrl, Instant checkedAt, Map<String, String> freshDigests,
                             boolean conditionsOnly, Set<String> retainedEvidence) {
            this.packageFingerprint = packageFingerprint;
            this.extractionFingerprint = extractionFingerprint;
            this.detailUrl = detailUrl;
            this.checkedAt = checkedAt;
            this.freshDigests = Map.copyOf(freshDigests);
            this.conditionsOnly = conditionsOnly;
            this.retainedEvidence = Set.copyOf(retainedEvidence);
        }

        public String packageFingerprint() {
            return packageFingerprint;
        }

        public String extractionFingerprint() {
            return extractionFingerprint;
        }

        public Instant checkedAt() {
            return checkedAt;
        }

        public String detailUrl() {
            return detailUrl;
        }

        public Map<String, String> freshDigests() {
            return freshDigests;
        }

        public boolean conditionsOnly() {
            return conditionsOnly;
        }

        public Set<String> retainedEvidence() {
            return retainedEvidence;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SourceReviewVerifier(AutomaticReviewRepository extractions, NoticeService notices,
                                AttachmentFingerprintClient http, ObjectMapper json, SupplementalSourceVerifier supplemental) {
        this(extractions, notices, http, json, Clock.systemUTC(), supplemental);
    }

    public SourceReviewVerifier(AutomaticReviewRepository extractions, NoticeService notices,
                                AttachmentFingerprintClient http, ObjectMapper json) {
        this(extractions, notices, http, json, Clock.systemUTC());
    }

    SourceReviewVerifier(AutomaticReviewRepository extractions, NoticeService notices,
                         AttachmentFingerprintClient http, ObjectMapper json, Clock clock) {
        this(extractions, notices, http, json, clock, new SupplementalSourceVerifier(http));
    }

    SourceReviewVerifier(AutomaticReviewRepository extractions, NoticeService notices,
                         AttachmentFingerprintClient http, ObjectMapper json, Clock clock, SupplementalSourceVerifier supplemental) {
        this.extractions = extractions;
        this.notices = notices;
        this.http = http;
        this.json = json;
        this.clock = clock;
        this.supplemental = supplemental;
    }

    public Verification verify(SourceReviewModels.Package p) throws Exception {
        return verify(p, false);
    }

    /**
     * Only unchanged prior schedules/issues may retain their already published ancillary evidence.
     */
    public Verification verifyConditions(SourceReviewModels.Package p) throws Exception {
        return verify(p, true);
    }

    private Verification verify(SourceReviewModels.Package p, boolean conditionsOnly) throws Exception {
        Instant verificationStartedAt = clock.instant();
        validate(p);
        var snapshot = extractions.snapshot(p.extractionId());
        var draft = snapshot.draft();
        if (!draft.noticeId().equals(p.noticeId().toString()) || draft.revision() != p.revision()
            || !extractions.matchesCurrentEvidence(p.noticeId(), p.revision(), p.extractionId(), snapshot.fingerprint()))
            throw ApiException.conflict();
        var original = notices.attachmentSnapshot(p.noticeId());
        if (original.revision() != p.revision()) throw ApiException.conflict();
        var storedDetail = detailSource(p.noticeId(), original.content().evidence().url(), draft.sources());
        var detailUrl = storedDetail.url();
        var detail = OfficialDetailParser.parse(http.html(detailUrl), detailUrl);
        if (!detail.parts().equals(storedDetail.context()))
            throw new IllegalArgumentException("OFFICIAL_DETAIL_CHANGED");
        Set<String> currentFiles = new HashSet<>();
        detail.attachments().forEach(s -> currentFiles.add(s.url()));
        Map<String, SourceReview> reviewed = new LinkedHashMap<>();
        p.sourceReviews().forEach(s -> reviewed.put(s.url(), s));
        boolean complete = !p.disposition().equals("HOLD");
        if (complete && (!reviewed.keySet().containsAll(currentFiles) || !reviewed.containsKey(detailUrl)
            || draft.sources().stream().anyMatch(s -> !reviewed.containsKey(s.url()))))
            throw new IllegalArgumentException("SOURCE_REVIEW_INCOMPLETE");
        if (p.supplementalReferences().stream().anyMatch(s -> s.url().equals(detailUrl) || currentFiles.contains(s.url())))
            throw new IllegalArgumentException("SUPPLEMENTAL_PRIMARY_COLLISION");
        var additional = supplemental.verify(p.supplementalReferences(), reviewed);
        Map<String, String> digests = new LinkedHashMap<>();
        for (var source : p.sourceReviews()) {
            if (source.url().equals(detailUrl)) {
                if (!Objects.equals(source.sha256(), storedDetail.sha256()))
                    throw new IllegalArgumentException("REVIEWED_DETAIL_SNAPSHOT_CHANGED");
                digests.put(source.url(), hash(json.writeValueAsString(detail.parts())));
                continue;
            }
            if (!currentFiles.contains(source.url())) {
                if (!additional.containsKey(source.url()))
                    throw new IllegalArgumentException("NOT_A_CURRENT_OFFICIAL_ATTACHMENT");
                digests.put(source.url(), additional.get(source.url()));
                continue;
            }
            var storedFile = draft.sources().stream().filter(s -> s.url().equals(source.url())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("REVIEWED_FILE_SNAPSHOT_MISSING"));
            if (!Objects.equals(source.sha256(), storedFile.sha256()))
                throw new IllegalArgumentException("REVIEWED_FILE_SNAPSHOT_CHANGED");
            var digest = http.fetchForSourceReview(source.url());
            if (!source.sha256().equals(digest.sha256())) throw new IllegalArgumentException("REVIEWED_FILE_CHANGED");
            digests.put(source.url(), digest.sha256());
        }
        Set<String> retained = new HashSet<>();
        if (complete && p.content() != null) {
            var c = p.content();
            Stream<HousingModels.Evidence> evidence = Stream.concat(Stream.of(c.evidence()), Stream.concat(c.attachments().stream(),
                Stream.concat(c.questions().stream().map(HousingModels.Question::evidence),
                    c.documents().stream().map(HousingModels.DocumentTemplate::evidence))));
            for (var schedule : c.schedules()) {
                if (conditionsOnly && original.content().schedules().contains(schedule))
                    retained.add(withoutFragment(schedule.evidence().url()));
                else evidence = Stream.concat(evidence, Stream.of(schedule.evidence()));
            }
            for (var issue : c.reviewIssues()) {
                if (conditionsOnly && original.content().reviewIssues().contains(issue))
                    issue.evidence().forEach(e -> retained.add(withoutFragment(e.url())));
                else evidence = Stream.concat(evidence, issue.evidence().stream());
            }
            if (p.confirmedOpenEndedPeriod() != null)
                evidence = Stream.concat(evidence, Stream.of(p.confirmedOpenEndedPeriod().evidence()));
            if (c.applicationClosure() != null)
                evidence = Stream.concat(evidence, Stream.of(c.applicationClosure().evidence()));
            if (evidence.anyMatch(e -> !reviewed.containsKey(withoutFragment(e.url()))))
                throw new IllegalArgumentException("CONTENT_HAS_UNREVIEWED_EVIDENCE");
        }
        retained.removeAll(reviewed.keySet());
        return new Verification(hash(json.writeValueAsString(p)), snapshot.fingerprint(), detailUrl, verificationStartedAt, digests, conditionsOnly, retained);
    }

    private Source detailSource(UUID noticeId, String evidenceUrl, List<Source> sources) {
        var uri = URI.create(withoutFragment(evidenceUrl));
        if (!"/lhapply/lhFile.do".equals(uri.getPath()))
            return sources.stream().filter(source -> source.url().equals(evidenceUrl)).findFirst().orElseThrow();
        if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost())
            || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
            || uri.getRawQuery() == null || !uri.getRawQuery().matches("fileid=[0-9]{1,20}"))
            throw new IllegalArgumentException("OFFICIAL_DETAIL_SOURCE_REQUIRED");
        // A published guide may cite a PDF. Only the collected identity can select its HTML detail.
        var sourceKey = notices.entity(noticeId).sourceKey;
        var candidates = sources.stream().filter(source -> "EXTRACTED".equals(source.status()))
            .filter(source -> {
                try {
                    OfficialCorrectionLink.identity(source.url());
                } catch (IllegalArgumentException unsupported) {
                    return false;
                }
                return LhNoticeClient.sourceKey(source.url()).equals(sourceKey);
            }).toList();
        if (candidates.size() != 1) throw new IllegalArgumentException("OFFICIAL_DETAIL_SOURCE_REQUIRED");
        return candidates.get(0);
    }

    public static void validate(SourceReviewModels.Package p) {
        if (p == null || p.packageId() == null || p.packageId().isBlank() || p.packageId().length() > 100
            || p.noticeId() == null || p.extractionId() == null || p.revision() < 1
            || !Set.of("PASS", "PARTIAL", "HOLD", "UNSUPPORTED").contains(p.disposition())
            || p.reviewNotes() == null || p.reviewNotes().isBlank() || p.reviewNotes().length() > 1800
            || p.sourceReviews() == null || p.sourceReviews().isEmpty() || p.sourceReviews().size() > 51
            || p.unresolvedFindings() == null)
            throw new IllegalArgumentException("INVALID_SOURCE_REVIEW");
        if (!Set.of("HOLD", "PARTIAL").contains(p.disposition()) && !p.unresolvedFindings().isEmpty())
            throw new IllegalArgumentException("UNRESOLVED_OFFICIAL_CONFLICT");
        if (p.applicationPeriodIssueId() != null && (!p.disposition().equals("PARTIAL")
            || !p.unresolvedFindings().contains(p.applicationPeriodIssueId())))
            throw new IllegalArgumentException("UNMAPPED_APPLICATION_PERIOD_ISSUE");
        if (p.disposition().equals("PARTIAL")) {
            if (p.content() == null || p.content().reviewIssues().isEmpty())
                throw new IllegalArgumentException("SCOPED_REVIEW_ISSUES_REQUIRED");
            var ids = p.content().reviewIssues().stream().map(HousingModels.ReviewIssue::id).toList();
            if (new HashSet<>(ids).size() != ids.size() || new HashSet<>(p.unresolvedFindings()).size() != p.unresolvedFindings().size()
                || !new HashSet<>(ids).equals(new HashSet<>(p.unresolvedFindings())))
                throw new IllegalArgumentException("UNMAPPED_OFFICIAL_CONFLICT");
        } else if (!p.disposition().equals("HOLD") && p.content() != null && !p.content().reviewIssues().isEmpty()) {
            throw new IllegalArgumentException("PARTIAL_REVIEW_DISPOSITION_REQUIRED");
        }
        Set<String> urls = new HashSet<>();
        for (var s : p.sourceReviews()) {
            EvidenceValidation.validate(new HousingModels.Evidence(s.label(), s.url(), java.time.LocalDate.now().toString()));
            if (!urls.add(s.url()) || s.sha256() == null || !s.sha256().matches("[0-9a-f]{64}")
                || s.role() == null || s.role().isBlank() || s.reviewNotes() == null || s.reviewNotes().isBlank())
                throw new IllegalArgumentException("INCOMPLETE_SOURCE_REVIEW");
        }
        if (p.supplementalReferences().size() > 10) throw new IllegalArgumentException("SUPPLEMENTAL_REFERENCE_LIMIT");
        Set<String> additional = new HashSet<>();
        for (var reference : p.supplementalReferences()) {
            SupplementalSourceVerifier.validate(reference);
            if (!additional.add(reference.url()))
                throw new IllegalArgumentException("DUPLICATE_SUPPLEMENTAL_REFERENCE");
        }
        if (p.confirmedOpenEndedPeriod() != null) EvidenceValidation.validate(p.confirmedOpenEndedPeriod().evidence());
    }

    static String withoutFragment(String url) {
        return url.split("#", 2)[0];
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
