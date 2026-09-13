package com.harupaper.server.render;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RenderRepository extends JpaRepository<Render, String> {
    List<Render> findAllByFormatIdOrderByRenderedAtDesc(String formatId);

    Optional<Render> findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
            String formatId, LocalDate targetDate, String profileKey);

    Optional<Render> findFirstByFormatIdAndProfileKeyOrderByRenderedAtDesc(String formatId, String profileKey);

    List<Render> findAllByKind(String kind);
}
