package com.harupaper.server.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ResultRepository extends JpaRepository<Result, String> {
    List<Result> findAllByOrderByExecutedAtDesc();

    List<Result> findAllByExecutedAtBeforeOrderByExecutedAtDesc(Instant before);

    /** M6: 사용자별 결과 조회 */
    List<Result> findAllByOwnerUserIdOrderByExecutedAtDesc(String ownerUserId);

    List<Result> findAllByOwnerUserIdAndExecutedAtBeforeOrderByExecutedAtDesc(String ownerUserId, Instant before);

    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Result r SET r.ownerUserId = :newOwnerId WHERE r.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
