package com.harupaper.server.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CommandRepository extends JpaRepository<Command, String> {
    List<Command> findAllByStatusIn(List<String> statuses);

    List<Command> findAllByStatusInAndCreatedAtBefore(List<String> statuses, Instant before);

    /** M6: 폴링한 기기로 스코핑(device 패키지가 쓴다). */
    List<Command> findAllByDeviceIdAndStatusIn(String deviceId, List<String> statuses);

    List<Command> findAllByDeviceIdAndStatusInAndCreatedAtBefore(String deviceId, List<String> statuses, Instant before);

    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Command c SET c.ownerUserId = :newOwnerId WHERE c.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
