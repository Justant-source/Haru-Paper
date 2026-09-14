package com.harupaper.server.schedule;

import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.format.FormatRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Schedule 도메인 서비스. 예약 CRUD와 nextOccurrenceAt 계산.
 */
@Service
public class ScheduleService {
    private final ScheduleRepository scheduleRepository;
    private final FormatRepository formatRepository;

    public ScheduleService(ScheduleRepository scheduleRepository, FormatRepository formatRepository) {
        this.scheduleRepository = scheduleRepository;
        this.formatRepository = formatRepository;
    }

    /**
     * 전체 예약 목록 조회 (켜짐/꺼짐 모두 포함, nextOccurrenceAt 계산)
     */
    public List<ScheduleResponseDto> findAll() {
        List<Schedule> schedules = scheduleRepository.findAll();
        List<ScheduleResponseDto> result = new ArrayList<>();
        for (Schedule schedule : schedules) {
            result.add(toResponseDto(schedule));
        }
        return result;
    }

    /**
     * 예약 생성 (검증: formatId 존재, time HH:mm, recurring이면 daysOfWeek 1개 이상, once이면 date 필수이고 과거면 422)
     */
    public ScheduleResponseDto create(CreateScheduleRequestDto request) {
        // formatId 존재 확인
        if (!formatRepository.existsById(request.formatId())) {
            throw new NotFoundException("Format not found: " + request.formatId());
        }

        // 검증: type별 필수 필드
        if ("recurring".equals(request.type())) {
            if (request.daysOfWeek() == null || request.daysOfWeek().isEmpty()) {
                throw new ValidationException("daysOfWeek is required for recurring schedule", List.of(
                        new ValidationException.FieldError("daysOfWeek", "must have at least one day")
                ));
            }
            validateRecurringDaysOfWeek(request.daysOfWeek());
            if (request.date() != null) {
                throw new ValidationException("date must be null for recurring schedule", List.of(
                        new ValidationException.FieldError("date", "must be null for recurring")
                ));
            }
        } else if ("once".equals(request.type())) {
            if (request.date() == null) {
                throw new ValidationException("date is required for once schedule", List.of(
                        new ValidationException.FieldError("date", "must not be null for once")
                ));
            }
            // 과거 시각이면 422
            LocalDate dateKst = request.date();
            LocalTime timeKst = parseTime(request.time());
            ZonedDateTime scheduledAt = dateKst.atTime(timeKst).atZone(TimeUtils.KST);
            if (scheduledAt.isBefore(TimeUtils.nowInKST())) {
                throw new ValidationException("scheduled date and time is in the past", List.of(
                        new ValidationException.FieldError("date", "must not be in the past")
                ));
            }
            if (request.daysOfWeek() != null) {
                throw new ValidationException("daysOfWeek must be null for once schedule", List.of(
                        new ValidationException.FieldError("daysOfWeek", "must be null for once")
                ));
            }
        } else {
            throw new ValidationException("Invalid schedule type: " + request.type(), List.of(
                    new ValidationException.FieldError("type", "must be 'recurring' or 'once'")
            ));
        }

        String scheduleId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Schedule schedule = Schedule.builder()
                .id(scheduleId)
                .formatId(request.formatId())
                .type(request.type())
                .daysOfWeek("recurring".equals(request.type()) ? String.join(",", request.daysOfWeek()) : null)
                .time(parseTime(request.time()))
                .date(request.date())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Schedule saved = scheduleRepository.save(schedule);
        return toResponseDto(saved);
    }

    /**
     * 예약 수정 (전체 교체, enabled 변경도 포함)
     */
    public ScheduleResponseDto update(String id, CreateScheduleRequestDto request) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Schedule not found: " + id));

        // formatId 존재 확인
        if (!formatRepository.existsById(request.formatId())) {
            throw new NotFoundException("Format not found: " + request.formatId());
        }

        // 동일한 검증
        if ("recurring".equals(request.type())) {
            if (request.daysOfWeek() == null || request.daysOfWeek().isEmpty()) {
                throw new ValidationException("daysOfWeek is required for recurring schedule", List.of(
                        new ValidationException.FieldError("daysOfWeek", "must have at least one day")
                ));
            }
            validateRecurringDaysOfWeek(request.daysOfWeek());
            if (request.date() != null) {
                throw new ValidationException("date must be null for recurring schedule", List.of(
                        new ValidationException.FieldError("date", "must be null for recurring")
                ));
            }
        } else if ("once".equals(request.type())) {
            if (request.date() == null) {
                throw new ValidationException("date is required for once schedule", List.of(
                        new ValidationException.FieldError("date", "must not be null for once")
                ));
            }
            // 과거 시각이면 422
            LocalDate dateKst = request.date();
            LocalTime timeKst = parseTime(request.time());
            ZonedDateTime scheduledAt = dateKst.atTime(timeKst).atZone(TimeUtils.KST);
            if (scheduledAt.isBefore(TimeUtils.nowInKST())) {
                throw new ValidationException("scheduled date and time is in the past", List.of(
                        new ValidationException.FieldError("date", "must not be in the past")
                ));
            }
            if (request.daysOfWeek() != null) {
                throw new ValidationException("daysOfWeek must be null for once schedule", List.of(
                        new ValidationException.FieldError("daysOfWeek", "must be null for once")
                ));
            }
        }

        schedule.setFormatId(request.formatId());
        schedule.setType(request.type());
        schedule.setDaysOfWeek("recurring".equals(request.type()) ? String.join(",", request.daysOfWeek()) : null);
        schedule.setTime(parseTime(request.time()));
        schedule.setDate(request.date());
        schedule.setEnabled(request.enabled() != null ? request.enabled() : true);
        schedule.setUpdatedAt(Instant.now());

        Schedule updated = scheduleRepository.save(schedule);
        return toResponseDto(updated);
    }

    /**
     * 예약 삭제
     */
    public void delete(String id) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Schedule not found: " + id));
        scheduleRepository.delete(schedule);
    }

    /**
     * Schedule 엔티티를 API 응답 DTO로 변환 (nextOccurrenceAt 계산 포함)
     */
    private ScheduleResponseDto toResponseDto(Schedule schedule) {
        Instant nextOccurrence = calculateNextOccurrence(schedule);
        String nextOccurrenceAtIso = nextOccurrence != null ? TimeUtils.toIso8601(nextOccurrence) : null;

        return new ScheduleResponseDto(
                schedule.getId(),
                schedule.getFormatId(),
                schedule.getType(),
                "recurring".equals(schedule.getType()) ? parseDaysOfWeek(schedule.getDaysOfWeek()) : null,
                schedule.getDate(),
                schedule.getTime(),
                schedule.getEnabled(),
                nextOccurrenceAtIso
        );
    }

    /**
     * nextOccurrenceAt 계산:
     * - enabled=false면 null
     * - recurring: 지금(KST) 이후 가장 가까운 (요일 ∈ daysOfWeek) & (그 요일의 time) 조합
     *   오늘 그 시각이 아직 안 지났으면 오늘도 후보
     * - once: date+time이 지금보다 미래면 그 값, 이미 지났으면 null
     */
    private Instant calculateNextOccurrence(Schedule schedule) {
        if (!schedule.getEnabled()) {
            return null;
        }

        ZonedDateTime nowKst = TimeUtils.nowInKST();

        if ("once".equals(schedule.getType())) {
            ZonedDateTime scheduledAt = schedule.getDate()
                    .atTime(schedule.getTime())
                    .atZone(TimeUtils.KST);
            if (scheduledAt.isAfter(nowKst)) {
                return scheduledAt.toInstant();
            }
            return null;
        }

        // recurring: 지금 이후 가장 가까운 occurrence 찾기
        Set<DayOfWeek> targetDays = parseDaysOfWeekToDayOfWeek(schedule.getDaysOfWeek());
        LocalTime targetTime = schedule.getTime();

        // 오늘부터 최대 7일 안에 검색
        for (int i = 0; i < 7; i++) {
            LocalDate checkDate = nowKst.plusDays(i).toLocalDate();
            DayOfWeek dayOfWeek = checkDate.getDayOfWeek();

            if (targetDays.contains(dayOfWeek)) {
                ZonedDateTime occurrence = checkDate.atTime(targetTime).atZone(TimeUtils.KST);
                if (occurrence.isAfter(nowKst)) {
                    return occurrence.toInstant();
                }
            }
        }

        // 이론상 도달 불가능 (7개 요일 모두 포함하면 항상 찾음)
        return null;
    }

    /**
     * "MON,TUE,WED" → List<String>
     */
    private List<String> parseDaysOfWeek(String daysOfWeekStr) {
        if (daysOfWeekStr == null || daysOfWeekStr.isEmpty()) {
            return List.of();
        }
        return List.of(daysOfWeekStr.split(","));
    }

    /**
     * "MON,TUE,WED" → Set<DayOfWeek>
     */
    private Set<DayOfWeek> parseDaysOfWeekToDayOfWeek(String daysOfWeekStr) {
        if (daysOfWeekStr == null || daysOfWeekStr.isEmpty()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        for (String day : daysOfWeekStr.split(",")) {
            // 문서·DB는 3글자 코드("MON".."SUN")를 쓴다. java.time.DayOfWeek는
            // 전체 이름(MONDAY..SUNDAY)이라 DayOfWeek.valueOf()를 바로 쓰면 안 된다
            // [확인됨: "No enum constant java.time.DayOfWeek.MON" 런타임 예외 실사 확인].
            result.add(dayCodeToDayOfWeek(day.trim().toUpperCase()));
        }
        return result;
    }

    private DayOfWeek dayCodeToDayOfWeek(String code) {
        return switch (code) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("unknown day code: " + code);
        };
    }

    /**
     * "HH:mm" → LocalTime
     */
    private LocalTime parseTime(String timeStr) {
        if (timeStr == null || !timeStr.matches("\\d{2}:\\d{2}")) {
            throw new ValidationException("Invalid time format", List.of(
                    new ValidationException.FieldError("time", "must be HH:mm format")
            ));
        }
        try {
            return LocalTime.parse(timeStr);
        } catch (DateTimeParseException e) {
            throw new ValidationException("Invalid time value", List.of(
                    new ValidationException.FieldError("time", "must be a valid 24-hour time")
            ));
        }
    }

    private void validateRecurringDaysOfWeek(List<String> daysOfWeek) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        for (String raw : daysOfWeek) {
            String code = raw == null ? "" : raw.trim().toUpperCase();
            try {
                dayCodeToDayOfWeek(code);
            } catch (IllegalArgumentException e) {
                errors.add(new ValidationException.FieldError("daysOfWeek", "invalid day code: " + raw));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("Invalid daysOfWeek", errors);
        }
    }
}
