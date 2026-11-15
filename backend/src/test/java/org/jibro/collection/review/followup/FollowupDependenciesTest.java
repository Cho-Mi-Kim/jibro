package org.jibro.collection.review.followup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.collection.review.followup.OfficialFollowupModels.*;

class FollowupDependenciesTest {
    private Related related(String file, String hash) {
        return new Related("FOLLOWUP","https://apply.lh.or.kr/notice","공식 후속", LocalDate.of(2026,11,1),"동일 공고",
            List.of(new Source("첨부",file,hash,"EXTRACTED","",List.of())));
    }
    private Discovery discovery(Related... related) { return new Discovery(Instant.now(),List.of(related),List.of()); }

    @Test void emptySearchAndMissingAttachmentDoNotRevokePriorCorrection() {
        var previous=discovery(related("https://apply.lh.or.kr/file1","a"));
        assertTrue(FollowupDependencies.missing(previous,discovery()));
        assertTrue(FollowupDependencies.missing(previous,discovery(related("https://apply.lh.or.kr/file2","a"))));
    }
    @Test void sameDependencyWithChangedBytesMustProceedToComparison() {
        assertFalse(FollowupDependencies.missing(discovery(related("https://apply.lh.or.kr/file1","a")),
            discovery(related("https://apply.lh.or.kr/file1","b"))));
        assertFalse(FollowupDependencies.missing(discovery(),discovery(related("https://apply.lh.or.kr/file1","a"))));
    }
}
