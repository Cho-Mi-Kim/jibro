package org.jibro.notice;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="notice")
public class NoticeEntity {
    @Id UUID id;
    @Column(name="source_key") String sourceKey;
    @Column(length=300,nullable=false) String title;
    @Column(length=100,nullable=false) String agency;
    @Column(length=100,nullable=false) String region;
    @Column(name="support_type",length=100,nullable=false) String supportType;
    LocalDate startDate,endDate,publishedDate;
    int version=1;
    int revision=1;
    @Column(name="basic_review") String basicReview="HOLD";
    @Column(name="guide_review") String guideReview="HOLD";
    boolean changeReview;
    String reason="";
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String content;
    protected NoticeEntity() {}
}
