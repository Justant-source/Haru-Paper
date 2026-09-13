package com.harupaper.server.schedule;

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
import java.time.LocalTime;

/**
 * formatId는 JPA 연관관계(@ManyToOne)를 쓰지 않고 문자열 컬럼으로 둔다(프로젝트 관례,
 * server/README.md "구현 규칙"). 참조 무결성은 DB FK(ON DELETE RESTRICT)가 보장한다.
 * daysOfWeek는 콤마 구분 문자열(예: "MON,TUE,WED") — SET 대신 VARCHAR로 구현
 * (docs/server/data-model.md 참고). 파싱/직렬화는 서비스 계층 책임이다.
 */
@Entity
@Table(name = "schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Schedule {

    @Id
    private String id;

    @Column(name = "format_id", nullable = false)
    private String formatId;

    /** "recurring" | "once" */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "days_of_week", length = 50)
    private String daysOfWeek;

    @Column(nullable = false)
    private LocalTime time;

    private LocalDate date;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
