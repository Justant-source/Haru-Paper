package com.harupaper.server.command;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CommandRepository extends JpaRepository<Command, String> {
    List<Command> findAllByStatusIn(List<String> statuses);

    List<Command> findAllByStatusInAndCreatedAtBefore(List<String> statuses, Instant before);
}
