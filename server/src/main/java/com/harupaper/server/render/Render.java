package com.harupaper.server.render;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** kind: "scheduled" | "preview" | "command" (문자열, docs/server/data-model.md). */
@Entity
@Table(name = "renders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Render {

    @Id
    private String id;

    @Column(name = "format_id", nullable = false)
    private String formatId;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "profile_key", nullable = false, length = 100)
    private String profileKey;

    @Column(name = "width_px", nullable = false)
    private Integer widthPx;

    @Column(name = "height_px", nullable = false)
    private Integer heightPx;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** haru-files 볼륨 기준 상대 경로: renders/{id}.png */
    @Column(nullable = false, length = 255)
    private String path;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "format_updated_at", nullable = false)
    private Instant formatUpdatedAt;

    @Column(name = "weather_fetched_at")
    private Instant weatherFetchedAt;

    @Column(name = "rendered_at", nullable = false)
    private Instant renderedAt;
}
