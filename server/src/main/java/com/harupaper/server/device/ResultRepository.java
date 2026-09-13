package com.harupaper.server.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ResultRepository extends JpaRepository<Result, String> {
    List<Result> findAllByOrderByExecutedAtDesc();

    List<Result> findAllByExecutedAtBeforeOrderByExecutedAtDesc(Instant before);
}
