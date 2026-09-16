package com.harupaper.server.command;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CommandRepository extends JpaRepository<Command, String> {
    List<Command> findAllByStatusIn(List<String> statuses);

    List<Command> findAllByStatusInAndCreatedAtBefore(List<String> statuses, Instant before);

    /** M6: 폴링한 기기로 스코핑(device 패키지가 쓴다). */
    List<Command> findAllByDeviceIdAndStatusIn(String deviceId, List<String> statuses);

    List<Command> findAllByDeviceIdAndStatusInAndCreatedAtBefore(String deviceId, List<String> statuses, Instant before);
}
