package org.jibro.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "querydsl_probe")
public class QuerydslProbe {
    @Id
    private Long id;

    @Column(nullable = false)
    private String region;

    @Column(name = "support_type", nullable = false)
    private String supportType;

    protected QuerydslProbe() {
    }

    public QuerydslProbe(Long id, String region, String supportType) {
        this.id = id;
        this.region = region;
        this.supportType = supportType;
    }
}
