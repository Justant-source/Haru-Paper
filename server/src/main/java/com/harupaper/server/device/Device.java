package com.harupaper.server.device;

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
 * 사용자 1인당 기기 1대(owner_user_id UNIQUE, .temp/03-플랫폼-작업지시서-v1.0.md Q10).
 * M6 이전에는 이 테이블이 항상 id=1인 단일 행이었다 — 지금은 등록·페어링으로 생성된다
 * (DeviceTokenIssueService / DevicePairingService).
 * printerProfile / printerStatus는 JSON 문자열로 저장한다(프로젝트 JSON 컬럼 관례).
 */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    private String id;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 50)
    private String name;

    /** SHA-256(토큰), hex 64자. 원문 토큰은 발급 시점에만 응답으로 보여주고 저장하지 않는다. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_issued_at", nullable = false)
    private Instant tokenIssuedAt;

    @Column(name = "last_poll_at")
    private Instant lastPollAt;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;

    @Column(name = "agent_version", length = 50)
    private String agentVersion;

    @Column(name = "printer_profile", columnDefinition = "JSON")
    private String printerProfile;

    @Column(name = "printer_status", columnDefinition = "JSON")
    private String printerStatus;

    /** "unverified" | "status_query" | "manual_flag" */
    @Column(name = "paper_policy", length = 20)
    private String paperPolicy;

    @Column(name = "paper_state_manual", nullable = false)
    private Boolean paperStateManual;

    @Column(name = "paper_state_updated_at")
    private Instant paperStateUpdatedAt;

    /** "app" | "server" */
    @Column(name = "paper_state_updated_by", length = 10)
    private String paperStateUpdatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
