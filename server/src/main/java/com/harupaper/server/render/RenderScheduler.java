package com.harupaper.server.render;

import com.harupaper.server.common.time.ClockProvider;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.device.PrinterProfileProvider;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatDocumentSupport;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.schedule.Schedule;
import com.harupaper.server.schedule.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 렌더 스케줄러 (docs/server/rendering.md 4절).
 * 매 5분마다 다음 36시간 안의 occurrence를 스캔하고, 필요시 렌더를 생성한다.
 *
 * 동작:
 * 1. 켜진 예약의 occurrence 중 지금부터 36시간 안의 것을 계산
 * 2. (formatId, targetDate)마다 최신 렌더가 없거나 stale하면 → 렌더 생성
 * 3. 동적 포맷(weather 블록)은 occurrence 60분 전에 한 번 더 렌더
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenderScheduler {

    private final ScheduleRepository scheduleRepository;
    private final FormatRepository formatRepository;
    private final RenderRepository renderRepository;
    private final RenderService renderService;
    private final PrinterProfileProvider printerProfileProvider;
    private final ClockProvider clockProvider;

    @Scheduled(fixedDelay = 300000)  // 5분 = 300,000ms
    @Transactional
    public void scanAndRender() {
        log.debug("RenderScheduler: scanning for upcoming occurrences");

        try {
            List<Schedule> enabledSchedules = scheduleRepository.findAllByEnabledTrue();
            String currentProfileKey = printerProfileProvider.getCurrentProfile().profileKey();

            for (Schedule schedule : enabledSchedules) {
                Format format = formatRepository.findById(schedule.getFormatId())
                        .orElse(null);
                if (format == null) {
                    log.warn("Format not found for schedule: {}", schedule.getId());
                    continue;
                }

                // Occurrence 계산 (지금부터 36시간 안)
                Set<LocalDate> occurrenceDates = calculateOccurrences(schedule);

                for (LocalDate targetDate : occurrenceDates) {
                    // 렌더 필요 여부 확인
                    if (shouldRender(format, targetDate, currentProfileKey)) {
                        try {
                            renderService.renderForScheduled(format.getId(), targetDate);
                            log.info("Scheduled render created: format={}, date={}", format.getId(), targetDate);
                        } catch (Exception e) {
                            log.error("Failed to render scheduled format: {} for {}",
                                    format.getId(), targetDate, e);
                        }
                    }

                    // 동적 포맷: occurrence 60분 전에 다시 렌더
                    if (FormatDocumentSupport.hasDynamicBlocks(parseFormatDocument(format))) {
                        if (shouldRenderBeforeOccurrence(format, targetDate, schedule.getTime(), currentProfileKey)) {
                            try {
                                renderService.renderForScheduled(format.getId(), targetDate);
                                log.info("Dynamic format re-render (60min before): format={}, date={}",
                                        format.getId(), targetDate);
                            } catch (Exception e) {
                                log.error("Failed to re-render dynamic format", e);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("RenderScheduler error", e);
        }
    }

    /**
     * 예약의 occurrence를 계산한다 (지금부터 36시간 안).
     * - recurring: daysOfWeek 기반
     * - once: date 기반 (1회만)
     */
    private Set<LocalDate> calculateOccurrences(Schedule schedule) {
        Set<LocalDate> dates = new HashSet<>();
        ZonedDateTime now = clockProvider.nowInKST();
        ZonedDateTime end = now.plusHours(36);

        if ("recurring".equals(schedule.getType())) {
            // daysOfWeek 파싱
            String[] daysStr = schedule.getDaysOfWeek() != null ?
                    schedule.getDaysOfWeek().split(",") : new String[]{};
            Set<java.time.DayOfWeek> daysOfWeek = new HashSet<>();
            for (String dayStr : daysStr) {
                try {
                    daysOfWeek.add(dayCodeToDayOfWeek(dayStr.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid day of week: {}", dayStr);
                }
            }

            // now ~ now+36h 범위에서 occurrence가 존재하는 날짜만 선택
            LocalDate cursor = now.toLocalDate();
            LocalDate last = end.toLocalDate();
            while (!cursor.isAfter(last)) {
                if (daysOfWeek.contains(cursor.getDayOfWeek())) {
                    ZonedDateTime occurrence = cursor.atTime(schedule.getTime()).atZone(TimeUtils.KST);
                    if (!occurrence.isBefore(now) && !occurrence.isAfter(end)) {
                        dates.add(cursor);
                    }
                }
                cursor = cursor.plusDays(1);
            }

        } else if ("once".equals(schedule.getType())) {
            // once: 일회성, date가 지정됨
            if (schedule.getDate() != null) {
                ZonedDateTime occurrence = schedule.getDate().atTime(schedule.getTime()).atZone(TimeUtils.KST);
                if (!occurrence.isBefore(now) && !occurrence.isAfter(end)) {
                    dates.add(schedule.getDate());
                }
            }
        }

        return dates;
    }

    /** schedules.days_of_week는 3글자 코드("MON".."SUN")다. DayOfWeek.valueOf()는 전체 이름을 요구한다. */
    private java.time.DayOfWeek dayCodeToDayOfWeek(String code) {
        return switch (code) {
            case "MON" -> java.time.DayOfWeek.MONDAY;
            case "TUE" -> java.time.DayOfWeek.TUESDAY;
            case "WED" -> java.time.DayOfWeek.WEDNESDAY;
            case "THU" -> java.time.DayOfWeek.THURSDAY;
            case "FRI" -> java.time.DayOfWeek.FRIDAY;
            case "SAT" -> java.time.DayOfWeek.SATURDAY;
            case "SUN" -> java.time.DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("unknown day code: " + code);
        };
    }

    /**
     * 렌더 필요 여부 판단:
     * - 최신 렌더가 없거나
     * - format.updatedAt < Render.formatUpdatedAt (포맷이 바뀜)이거나
     * - profileKey가 다르면 → true
     */
    private boolean shouldRender(Format format, LocalDate targetDate, String currentProfileKey) {
        Optional<Render> latest = renderRepository.findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
                format.getId(), targetDate, currentProfileKey);

        if (latest.isEmpty()) {
            return true;  // 렌더 없음 → 새로 생성
        }

        Render render = latest.get();

        // 포맷이 최근에 업데이트되었는가?
        if (format.getUpdatedAt().isAfter(render.getFormatUpdatedAt())) {
            return true;  // 포맷이 바뀜 → 재렌더
        }

        // profileKey 변경
        if (!currentProfileKey.equals(render.getProfileKey())) {
            return true;  // 프로필이 바뀜 → 재렌더
        }

        return false;
    }

    /**
     * 동적 포맷 60분 전 재렌더 판단:
     * - occurrence까지 60분 이하로 남았고
     * - 최신 렌더의 renderedAt이 occurrence - 60분보다 이전이면 → true
     *
     * docs/server/rendering.md 4절 항목 4:
     * "동적 포맷(has_dynamic_blocks = true)은 occurrence 약 60분 전에 한 번 더 렌더
     *  조건: occurrence까지 60분 이하로 남았고, 최신 렌더의 rendered_at이 occurrence − 60분보다 이전"
     */
    private boolean shouldRenderBeforeOccurrence(Format format, LocalDate targetDate,
                                                  LocalTime occurrenceTime, String currentProfileKey) {
        ZonedDateTime now = clockProvider.nowInKST();
        ZonedDateTime occurrence = targetDate.atTime(occurrenceTime).atZone(TimeUtils.KST);

        // 60분 이내?
        long minutesUntilOccurrence = ChronoUnit.MINUTES.between(now, occurrence);
        if (minutesUntilOccurrence > 60 || minutesUntilOccurrence < 0) {
            return false;  // 60분을 넘거나 이미 지남
        }

        // 최신 렌더가 60분 전보다 이전?
        Optional<Render> latest = renderRepository.findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
                format.getId(), targetDate, currentProfileKey);

        if (latest.isEmpty()) {
            return true;  // 렌더 없음 → 생성
        }

        ZonedDateTime sixtyMinutesBeforeOccurrence = occurrence.minusMinutes(60);
        return latest.get().getRenderedAt().isBefore(sixtyMinutesBeforeOccurrence.toInstant());
    }

    /**
     * Format.body (JSON)을 FormatDocument로 파싱 (간단한 버전, 에러 무시)
     */
    private com.harupaper.server.format.FormatDocument parseFormatDocument(Format format) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            return mapper.readValue(format.getBody(), com.harupaper.server.format.FormatDocument.class);
        } catch (Exception e) {
            log.warn("Failed to parse format document: {}", format.getId());
            return null;
        }
    }
}
