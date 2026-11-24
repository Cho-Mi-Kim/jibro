package org.jibro.notice;

import jakarta.persistence.*;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of the officially verified identity relation; writes stay in its repository.
 */
@Entity
@Immutable
@Table(name = "notice_correction")
class NoticeCorrectionEntity {
    @Id
    @Column(name = "original_notice_id")
    UUID originalId;
    @Column(name = "corrected_notice_id")
    UUID correctedId;

    protected NoticeCorrectionEntity() {
    }
}
