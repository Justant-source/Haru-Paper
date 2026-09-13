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
 * Pi 1대(PoC). 항상 id=1인 행 하나만 존재한다(애플리케이션 시작 시 없으면 생성).
 * printerProfile / printerStatus는 JSON 문자열로 저장한다(프로젝트 JSON 컬럼 관례,
 * server/README.md "구현 규칙").
 */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @Column(columnDefinition = "TINYINT")
    private Integer id;

    @Column(name = "last_poll_at")
    private Instant lastPollAt;

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
}
