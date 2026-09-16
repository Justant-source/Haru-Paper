package com.harupaper.server.asset;

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

@Entity
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    @Id
    private String id;

    /** NULL 허용 — M6 이전 레거시 행은 관리자가 claim-legacy로 채운다. */
    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    @Column(name = "width_px", nullable = false)
    private Integer widthPx;

    @Column(name = "height_px", nullable = false)
    private Integer heightPx;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** haru-files 볼륨 기준 상대 경로: uploads/{id}.png | uploads/{id}.jpg */
    @Column(nullable = false, length = 255)
    private String path;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
