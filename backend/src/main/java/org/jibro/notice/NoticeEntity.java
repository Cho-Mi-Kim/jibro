package org.jibro.notice;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notice")
public class NoticeEntity {
    @Id
    UUID id;
    @Column(name = "source_key")
    String sourceKey;
    String sourceFingerprint;
    boolean sourceClosed;
    boolean serviceClosed;
    boolean serviceExcluded;
    String serviceDecisionReason = "";
    Instant serviceDecidedAt;
    @Column(length = 300, nullable = false)
    String title;
    @Column(length = 100, nullable = false)
    String agency;
    @Column(length = 100, nullable = false)
    String region;
    @Column(name = "support_type", length = 100, nullable = false)
    String supportType;
    LocalDate startDate, endDate, publishedDate;
    Instant applicationStartAt, applicationEndAt;
    Instant basicCheckedAt, guideCheckedAt, collectedAt;
    int version = 1;
    int revision = 1;
    @Column(name = "basic_review")
    String basicReview = "HOLD";
    @Column(name = "guide_review")
    String guideReview = "HOLD";
    boolean changeReview;
    boolean automaticReviewBlocked;
    String reason = "";
    String lifecycleState = "NORMAL";
    String lifecycleReason = "";
    int lifecycleRevision;
    Instant lifecycleCheckedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    String lifecycleEvidence;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    String content;

    protected NoticeEntity() {
    }
}
