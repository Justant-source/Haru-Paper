package com.harupaper.server.format;

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

/**
 * body는 포맷 문서 전체(FormatDocument)를 JSON 문자열로 저장한다(assets 제외).
 * JSON 컬럼 관례는 server/README.md "구현 규칙" 참고 — Hibernate JSON 매핑을 쓰지 않고
 * 서비스 계층에서 ObjectMapper로 FormatDocument ↔ String을 변환한다.
 */
@Entity
@Table(name = "formats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Format {

    @Id
    private String id;

    /** NULL 허용 — M6 이전 레거시 행은 관리자가 claim-legacy로 채운다. */
    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(nullable = false, columnDefinition = "JSON")
    private String body;

    @Column(name = "has_dynamic_blocks", nullable = false)
    private Boolean hasDynamicBlocks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
