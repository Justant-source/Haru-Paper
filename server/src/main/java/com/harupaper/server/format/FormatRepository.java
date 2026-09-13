package com.harupaper.server.format;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormatRepository extends JpaRepository<Format, String> {
    List<Format> findAllByOrderByUpdatedAtDesc();
}
