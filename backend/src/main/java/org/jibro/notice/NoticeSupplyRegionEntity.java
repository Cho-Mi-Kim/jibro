package org.jibro.notice;

import jakarta.persistence.*;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "notice_supply_region")
class NoticeSupplyRegionEntity {
    @Id
    String id;
    UUID noticeId;
    String sourceFingerprint;
    String region;
}
