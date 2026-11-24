package org.jibro.collection.review.followup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Fetched official evidence and the explicit relationship that permits comparing it.
 */
public final class OfficialFollowupModels {
    private OfficialFollowupModels() {
    }

    public static boolean agencyHost(String host) {
        return host != null && (host.endsWith(".go.kr") || host.equals("www.kohom.or.kr")
            || host.equals("www.xn--v69aylw70b3vla29io83afif.kr") || host.equals("xn--v69aylw70b3vla29io83afif.kr"));
    }

    public record Target(UUID noticeId, int revision, String primaryUrl, String title, LocalDate publishedDate, boolean activePreparation) {
        public Target(UUID noticeId, int revision, String primaryUrl, String title, LocalDate publishedDate) {
            this(noticeId, revision, primaryUrl, title, publishedDate, true);
        }
    }

    public record Related(String kind, String url, String title, LocalDate publishedDate,
                          String relationshipProof, List<Source> sources, String supersedesUrl) {
        public Related(String kind, String url, String title, LocalDate publishedDate, String relationshipProof, List<Source> sources) {
            this(kind, url, title, publishedDate, relationshipProof, sources, null);
        }

        public Related {
            sources = List.copyOf(sources);
        }
    }

    /**
     * Blockers describe unfinished machine work, never an invented agency contradiction.
     */
    public record Discovery(Instant checkedAt, List<Related> related, List<String> blockers) {
        public Discovery {
            related = List.copyOf(related);
            blockers = List.copyOf(blockers);
        }

        public boolean hasMirror() {
            return related.stream().anyMatch(r -> r.url().matches("https://www[.]myhome[.]go[.]kr/hws/portal/sch/selectRsdtRcritNtcDetailView[.]do[?]pblancId=[0-9]{1,12}"));
        }

        public boolean permitsVerifiedItems() {
            return complete() || hasMirror()
                && blockers.stream().allMatch(b -> b.equals("AGENCY_PREPARATION_RETRY") || b.equals("REGIONAL_ALTERNATIVE_RETRY")
                    || b.equals("MYHOME_ALTERNATIVE_RETRY")
                    || b.equals("FOLLOWUP_ATTACHMENT_UNREAD") && related.stream().anyMatch(r -> "CORRECTION".equals(r.kind()))
                        && related.stream().noneMatch(r -> "FOLLOWUP".equals(r.kind())));
        }

        public boolean complete() {
            return blockers.isEmpty();
        }
    }
}
