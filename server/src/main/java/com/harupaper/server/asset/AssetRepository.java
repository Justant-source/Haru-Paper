package com.harupaper.server.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, String> {
    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Asset a SET a.ownerUserId = :newOwnerId WHERE a.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
