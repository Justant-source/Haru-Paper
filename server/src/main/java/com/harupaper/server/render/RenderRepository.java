package com.harupaper.server.render;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RenderRepository extends JpaRepository<Render, String> {
    List<Render> findAllByFormatIdOrderByRenderedAtDesc(String formatId);

    Optional<Render> findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
            String formatId, LocalDate targetDate, String profileKey);

    Optional<Render> findFirstByFormatIdAndProfileKeyOrderByRenderedAtDesc(String formatId, String profileKey);

    List<Render> findAllByKind(String kind);

    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Render r SET r.ownerUserId = :newOwnerId WHERE r.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
