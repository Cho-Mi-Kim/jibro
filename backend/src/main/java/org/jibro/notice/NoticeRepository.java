package org.jibro.notice;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<NoticeEntity,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from NoticeEntity n where n.id=:id")
    Optional<NoticeEntity> locked(@Param("id") UUID id);
    Optional<NoticeEntity> findBySourceKey(String key);
}
