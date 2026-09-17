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

    /**
     * M6: 폴링한 기기의 **소유자**로 스코핑. poll이 쓰는 것은 이쪽이다.
     *
     * device_id가 아니라 owner_user_id로 거르는 이유: "지금 인쇄"는 기기 페어링 전에도 만들 수 있고
     * (PrintNowController가 device가 없으면 device_id를 NULL로 둔다), device_id로 거르면 그 명령이
     * 나중에 페어링해도 영영 전달되지 않고 10분 뒤 조용히 expired가 된다.
     * V2의 `uk_devices_owner`(owner_user_id UNIQUE)가 1인 1기기를 보장하므로 소유자 스코핑은
     * 기기 스코핑과 보안상 동등하다.
     */
    List<Command> findAllByOwnerUserIdAndStatusIn(String ownerUserId, List<String> statuses);

    List<Command> findAllByDeviceIdAndStatusInAndCreatedAtBefore(String deviceId, List<String> statuses, Instant before);

    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Command c SET c.ownerUserId = :newOwnerId WHERE c.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
