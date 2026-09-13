package com.harupaper.server.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Pi가 발급한 resultId를 PK로 써서 멱등 업로드를 구현한다(POST /api/device/results).
 * FK를 걸지 않는다 — 포맷 삭제 후에도 이력 보존(docs/server/data-model.md).
 * History(schedule 패키지)는 이 리포지토리를 읽기 전용으로 참조한다.
 */
@Entity
@Table(name = "results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Result {

    @Id
    private String id;

    @Column(name = "occurrence_key", length = 80)
    private String occurrenceKey;

    @Column(name = "command_id")
    private String commandId;

    @Column(name = "format_id")
    private String formatId;

    @Column(name = "render_id")
    private String renderId;

    /** printed | dry_run | missed | failed | skipped_no_paper | skipped_clock_unsynced | skipped_printer_offline */
    @Column(nullable = false, length = 30)
    private String status;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
