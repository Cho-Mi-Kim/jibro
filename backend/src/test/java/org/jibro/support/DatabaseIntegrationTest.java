package org.jibro.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabaseIntegrationTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JPAQueryFactory queryFactory;

    @Test
    void generatedQuerydslTypeFiltersRealPostgresqlDataWithAndAndOr() {
        entityManager.persist(new QuerydslProbe(1L, "SEOUL", "RENT"));
        entityManager.persist(new QuerydslProbe(2L, "GYEONGGI", "RENT"));
        entityManager.persist(new QuerydslProbe(3L, "SEOUL", "LOAN"));
        entityManager.persist(new QuerydslProbe(4L, "BUSAN", "RENT"));
        entityManager.flush();
        entityManager.clear();

        QQuerydslProbe probe = QQuerydslProbe.querydslProbe;
        var ids = queryFactory.select(probe.id).from(probe)
                .where(probe.region.eq("SEOUL").or(probe.region.eq("GYEONGGI")),
                        probe.supportType.eq("RENT"))
                .orderBy(probe.id.asc()).fetch();

        assertEquals(java.util.List.of(1L, 2L), ids);
    }
}
