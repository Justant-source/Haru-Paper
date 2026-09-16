package com.harupaper.server.command;

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
 * "지금 인쇄" 명령(예약이 아니다). status: "pending" | "delivered" | "done" | "expired"
 * (문자열, docs/server/data-model.md). Device 도메인이 poll/results 처리 중 상태를 전이시킨다.
 */
@Entity
@Table(name = "commands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Command {

    @Id
    private String id;

    /** 명령 생성 시점 요청자·대상 기기. FK 없음(이력 보존, 프로젝트 관례). */
    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(name = "device_id")
    private String deviceId;

    /** 현재는 "print_now"뿐 */
    @Column(nullable = false, length = 20)
    private String type;

    /** FK 없음 — 포맷 삭제 후에도 이력 보존 */
    @Column(name = "format_id", nullable = false)
    private String formatId;

    @Column(name = "render_id")
    private String renderId;

    @Column(name = "paper_confirmed", nullable = false)
    private Boolean paperConfirmed;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
