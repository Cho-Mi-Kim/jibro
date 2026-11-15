package org.jibro.notice;

import java.util.*;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<NoticeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NoticeEntity n where n.id=:id")
    Optional<NoticeEntity> locked(@Param("id") UUID id);

    @Query(value = "select cast(manifest as text) from notice_file_inventory where notice_id=:id and source_fingerprint=:fingerprint", nativeQuery = true)
    Optional<String> officialFiles(@Param("id") UUID id, @Param("fingerprint") String fingerprint);

    Optional<NoticeEntity> findBySourceKey(String key);
}
