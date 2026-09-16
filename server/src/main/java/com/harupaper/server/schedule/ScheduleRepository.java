package com.harupaper.server.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    List<Schedule> findAllByFormatId(String formatId);

    List<Schedule> findAllByEnabledTrue();

    /** M6: 사용자 소유 예약만(앱 CRUD 스코핑, auth 패키지 컨트롤러가 쓴다). */
    List<Schedule> findAllByOwnerUserId(String ownerUserId);

    /** M6: 기기 동기화·렌더 스케줄러가 소유자별로 쓴다(device/render 패키지). */
    List<Schedule> findAllByOwnerUserIdAndEnabledTrue(String ownerUserId);

    /** M6: 레거시 리소스 소유권 이전 */
    @Modifying
    @Query("UPDATE Schedule s SET s.ownerUserId = :newOwnerId WHERE s.ownerUserId IS NULL")
    int updateOwnerForNull(@Param("newOwnerId") String newOwnerId);
}
