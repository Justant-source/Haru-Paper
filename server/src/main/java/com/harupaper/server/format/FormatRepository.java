package com.harupaper.server.format;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FormatRepository extends JpaRepository<Format, String> {

    /** M6: 사용자별 포맷 목록 조회 */
    List<Format> findByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId);

    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Format f SET f.ownerUserId = :newOwnerId WHERE f.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
