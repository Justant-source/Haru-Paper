package com.harupaper.server.device;

import com.harupaper.server.command.Command;
import com.harupaper.server.command.CommandRepository;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.render.Render;
import com.harupaper.server.render.RenderRepository;
import com.harupaper.server.render.RenderScanTrigger;
import com.harupaper.server.schedule.Schedule;
import com.harupaper.server.schedule.ScheduleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Device 동기화 서비스 (Pi 폴링 처리).
 * poll, snapshot, results 엔드포인트의 비즈니스 로직을 담당한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceSyncService {

    private final DeviceRepository deviceRepository;
    private final CommandRepository commandRepository;
    private final RenderRepository renderRepository;
    private final ScheduleRepository scheduleRepository;
    private final ResultIngestService resultIngestService;
    private final ObjectMapper objectMapper;
    private final SnapshotHashCalculator snapshotHashCalculator;
    private final RenderScanTrigger renderScanTrigger;

    @Value("${haru.poll-interval-sec:30}")
    private Integer pollIntervalSec;

    @Value("${haru.files-dir:/data/haru-files}")
    private String filesDir;

    /**
     * POST /api/device/poll 처리
     * (docs/server/api.md 6절 "poll 처리 순서")
     */
    @Transactional
    public DeviceDto.PollResponse processPoll(Device device, DeviceDto.PollRequest request) {
        Instant now = Instant.now();

        // 1. device 행 갱신
        String prevProfileKey = device.getPrinterProfile() != null ?
                getProfileKey(device.getPrinterProfile()) : null;

        device.setLastPollAt(now);
        device.setAgentVersion(request.agentVersion());
        if (request.printerProfile() != null) {
            try {
                device.setPrinterProfile(objectMapper.writeValueAsString(request.printerProfile()));
            } catch (Exception e) {
                log.error("Failed to serialize printer profile", e);
            }
        }
        if (request.printerStatus() != null) {
            try {
                device.setPrinterStatus(objectMapper.writeValueAsString(request.printerStatus()));
            } catch (Exception e) {
                log.error("Failed to serialize printer status", e);
            }
        }
        if (request.paperPolicy() != null) {
            device.setPaperPolicy(request.paperPolicy());
        }
        device = deviceRepository.save(device);

        String newProfileKey = device.getPrinterProfile() != null ?
                getProfileKey(device.getPrinterProfile()) : null;
        if (newProfileKey != null && !newProfileKey.equals(prevProfileKey)) {
            renderScanTrigger.requestScan();
        }

        // 3. 현재 스냅샷의 snapshotHash 계산 → snapshotChanged 확인
        DeviceDto.SnapshotResponse snapshot = buildSnapshot(device);
        String currentHash = snapshotHashCalculator.calculate(snapshot.schedules(), snapshot.renders());
        boolean snapshotChanged = !currentHash.equals(request.snapshotHash());

        // 4. 만료 처리 — 전역으로 스캔한다(기기별로 스코핑하면 페어링만 되고 한 번도 폴링하지 않은
        // 기기의 명령이 영영 만료되지 않고 pending으로 남기 때문). 상태만 "expired"로 바꿀 뿐 다른 사용자에게
        // 아무것도 노출하지 않으므로 전역 스캔이어도 소유권 경계를 침범하지 않는다.
        Instant expireThreshold = now.minusSeconds(600); // 10분
        List<Command> expiredCommands = commandRepository.findAllByStatusInAndCreatedAtBefore(
                List.of("pending", "delivered"), expireThreshold);
        for (Command cmd : expiredCommands) {
            cmd.setStatus("expired");
        }
        if (!expiredCommands.isEmpty()) {
            commandRepository.saveAll(expiredCommands);
        }

        // 5. 이 기기의 소유자로 스코핑해 pending+delivered 명령만 commands[]로 실어 보내고, pending은 delivered로 상태 변경.
        // 예전에는 전역 조회라 다른 사용자의 "지금 인쇄"가 이 기기로도 내려갔다(IDOR류 버그).
        // device_id가 아니라 owner_user_id로 거르는 이유는 CommandRepository.findAllByOwnerUserIdAndStatusIn
        // 주석 참조 — 페어링 전에 만든 명령(device_id가 NULL)을 조용히 버리지 않기 위해서다.
        // V2의 uk_devices_owner(owner_user_id UNIQUE)가 1인 1기기를 보장하므로 기기 스코핑과 보안상 동등하다.
        List<Command> activeCommands = commandRepository.findAllByOwnerUserIdAndStatusIn(
                device.getOwnerUserId(), List.of("pending", "delivered"));
        List<DeviceDto.CommandDto> commandDtos = new ArrayList<>();

        for (Command cmd : activeCommands) {
            // 렌더의 SHA-256 조회
            String sha256 = "";
            if (cmd.getRenderId() != null) {
                Render render = renderRepository.findById(cmd.getRenderId()).orElse(null);
                if (render != null) {
                    sha256 = render.getSha256();
                }
            }

            commandDtos.add(new DeviceDto.CommandDto(
                    cmd.getId(),
                    cmd.getType(),
                    cmd.getFormatId(),
                    cmd.getRenderId(),
                    sha256,
                    cmd.getPaperConfirmed(),
                    TimeUtils.toIso8601(cmd.getCreatedAt())
            ));

            // pending이면 delivered로 변경
            if ("pending".equals(cmd.getStatus())) {
                cmd.setStatus("delivered");
                cmd.setDeliveredAt(now);
            }
        }
        if (!activeCommands.isEmpty()) {
            commandRepository.saveAll(activeCommands);
        }

        // paperState
        DeviceDto.PaperState paperState = new DeviceDto.PaperState(
                device.getPaperStateManual(),
                device.getPaperStateUpdatedAt() != null ? TimeUtils.toIso8601(device.getPaperStateUpdatedAt()) : null,
                device.getPaperStateUpdatedBy()
        );

        // 응답
        return new DeviceDto.PollResponse(
                TimeUtils.toIso8601(now),
                currentHash,
                snapshotChanged,
                commandDtos,
                paperState,
                pollIntervalSec
        );
    }

    /**
     * GET /api/device/snapshot 응답 빌드
     */
    @Transactional(readOnly = true)
    public DeviceDto.SnapshotResponse buildSnapshot(Device device) {
        String ownerUserId = device.getOwnerUserId();

        // 그 기기 소유자의 모든 예약
        List<Schedule> allSchedules = scheduleRepository.findAllByOwnerUserId(ownerUserId);
        List<DeviceDto.ScheduleDto> scheduleDtos = allSchedules.stream()
                .map(this::toScheduleDto)
                .sorted(Comparator.comparing(DeviceDto.ScheduleDto::id))
                .collect(Collectors.toList());

        // 켜진 예약이 참조하는 포맷들의 렌더를 포함
        List<Schedule> enabledSchedules = scheduleRepository.findAllByOwnerUserIdAndEnabledTrue(ownerUserId);
        Set<String> enabledFormatIds = enabledSchedules.stream()
                .map(Schedule::getFormatId)
                .collect(Collectors.toSet());

        // 현재 profileKey 계산
        PrinterProfile profile = getCurrentProfile(device);
        String profileKey = profile.profileKey();

        // 36시간 안의 occurrence 날짜를 포맷별로 계산
        ZonedDateTime now = TimeUtils.nowInKST();
        ZonedDateTime end = now.plusHours(36);
        java.util.Map<String, Set<LocalDate>> formatTargetDates = new java.util.HashMap<>();
        for (Schedule schedule : enabledSchedules) {
            Set<LocalDate> upcomingDates = calculateUpcomingOccurrenceDates(schedule, now, end);
            if (upcomingDates.isEmpty()) {
                continue;
            }
            formatTargetDates.computeIfAbsent(schedule.getFormatId(), _ignored -> new HashSet<>())
                    .addAll(upcomingDates);
        }

        List<DeviceDto.RenderDto> renderDtos = new ArrayList<>();
        Set<String> addedRenderIds = new HashSet<>();

        for (String formatId : enabledFormatIds) {
            // 지금부터 36시간 안 occurrence의 (formatId, targetDate)별 최신 렌더
            for (LocalDate targetDate : formatTargetDates.getOrDefault(formatId, Set.of())) {
                Optional<Render> render = renderRepository.findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
                        formatId, targetDate, profileKey);
                if (render.isPresent() && !addedRenderIds.contains(render.get().getId())) {
                    renderDtos.add(toRenderDto(render.get()));
                    addedRenderIds.add(render.get().getId());
                }
            }

            // 날짜와 무관한 그 포맷의 최신 렌더 1개 (오프라인 폴백용)
            Optional<Render> latestRender = renderRepository.findFirstByFormatIdAndProfileKeyOrderByRenderedAtDesc(
                    formatId, profileKey);
            if (latestRender.isPresent() && !addedRenderIds.contains(latestRender.get().getId())) {
                renderDtos.add(toRenderDto(latestRender.get()));
                addedRenderIds.add(latestRender.get().getId());
            }
        }

        // hash 계산
        String snapshotHash = snapshotHashCalculator.calculate(scheduleDtos, renderDtos);

        return new DeviceDto.SnapshotResponse(
                snapshotHash,
                TimeUtils.toIso8601(Instant.now()),
                scheduleDtos,
                renderDtos
        );
    }

    /**
     * POST /api/device/results 처리 (멱등)
     */
    @Transactional
    public DeviceDto.ResultsResponse processResults(Device device, DeviceDto.ResultsRequest request) {
        List<String> accepted = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.results() == null) {
            throw new ValidationException("results is required", List.of(
                    new ValidationException.FieldError("results", "must not be null")
            ));
        }

        for (int i = 0; i < request.results().size(); i++) {
            DeviceDto.ResultDto resultDto = request.results().get(i);
            String path = "results[" + i + "]";
            boolean hasOccurrence = resultDto.occurrenceKey() != null && !resultDto.occurrenceKey().isBlank();
            boolean hasCommand = resultDto.commandId() != null && !resultDto.commandId().isBlank();
            if (hasOccurrence == hasCommand) {
                errors.add(new ValidationException.FieldError(path,
                        "exactly one of occurrenceKey or commandId is required"));
            }
            if (resultDto.executedAt() == null || resultDto.executedAt().isBlank()) {
                errors.add(new ValidationException.FieldError(path + ".executedAt", "executedAt is required"));
            } else {
                try {
                    TimeUtils.parseIso8601(resultDto.executedAt());
                } catch (Exception e) {
                    errors.add(new ValidationException.FieldError(path + ".executedAt",
                            "must be ISO-8601 with offset"));
                }
            }
            if (resultDto.scheduledAt() != null && !resultDto.scheduledAt().isBlank()) {
                try {
                    TimeUtils.parseIso8601(resultDto.scheduledAt());
                } catch (Exception e) {
                    errors.add(new ValidationException.FieldError(path + ".scheduledAt",
                            "must be ISO-8601 with offset"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("results payload is invalid", errors);
        }

        for (DeviceDto.ResultDto resultDto : request.results()) {
            if (resultIngestService.ingestNew(device, resultDto)) {
                accepted.add(resultDto.resultId());
            } else {
                duplicates.add(resultDto.resultId());
            }
        }

        return new DeviceDto.ResultsResponse(accepted, duplicates);
    }

    /**
     * 프린터 프로필 조회 (주어진 device의 값)
     */
    private PrinterProfile getCurrentProfile(Device device) {
        if (device != null && device.getPrinterProfile() != null && !device.getPrinterProfile().isBlank()) {
            try {
                return objectMapper.readValue(device.getPrinterProfile(), PrinterProfile.class);
            } catch (Exception e) {
                log.warn("Failed to parse printer profile, using DEFAULT", e);
            }
        }
        return PrinterProfile.DEFAULT;
    }

    /**
     * device의 printer_profile JSON에서 profileKey를 추출한다.
     */
    private String getProfileKey(String profileJson) {
        try {
            PrinterProfile profile = objectMapper.readValue(profileJson, PrinterProfile.class);
            return profile.profileKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Schedule을 ScheduleDto로 변환
     */
    private DeviceDto.ScheduleDto toScheduleDto(Schedule s) {
        List<String> daysOfWeek = null;
        String date = null;

        if ("recurring".equals(s.getType()) && s.getDaysOfWeek() != null) {
            daysOfWeek = s.getDaysOfWeek().isEmpty() ? null : List.of(s.getDaysOfWeek().split(","));
        } else if ("once".equals(s.getType()) && s.getDate() != null) {
            date = s.getDate().toString();
        }

        return new DeviceDto.ScheduleDto(
                s.getId(),
                s.getFormatId(),
                s.getType(),
                daysOfWeek,
                formatTime(s.getTime()),
                date,
                s.getEnabled()
        );
    }

    /**
     * LocalTime을 "HH:mm" 형식 문자열로
     */
    private String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /**
     * Render를 RenderDto로 변환
     */
    private DeviceDto.RenderDto toRenderDto(Render r) {
        // PBM은 V3 마이그레이션 이후에 만들어진 렌더에만 있다(architecture.md 3.4). 없으면 둘 다 null.
        boolean hasPbm = r.getPbmPath() != null;
        return new DeviceDto.RenderDto(
                r.getId(),
                r.getFormatId(),
                r.getTargetDate().toString(),
                r.getSha256(),
                r.getWidthPx(),
                TimeUtils.toIso8601(r.getRenderedAt()),
                "/api/device/renders/" + r.getId() + ".png",
                hasPbm ? "/api/device/renders/" + r.getId() + ".pbm" : null,
                hasPbm ? r.getPbmSha256() : null
        );
    }

    private Set<LocalDate> calculateUpcomingOccurrenceDates(Schedule schedule, ZonedDateTime now, ZonedDateTime end) {
        Set<LocalDate> dates = new HashSet<>();
        if (schedule.getTime() == null) {
            return dates;
        }

        if ("recurring".equals(schedule.getType())) {
            Set<java.time.DayOfWeek> daysOfWeek = parseDayCodes(schedule.getDaysOfWeek());
            if (daysOfWeek.isEmpty()) {
                return dates;
            }

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
        } else if ("once".equals(schedule.getType()) && schedule.getDate() != null) {
            ZonedDateTime occurrence = schedule.getDate().atTime(schedule.getTime()).atZone(TimeUtils.KST);
            if (!occurrence.isBefore(now) && !occurrence.isAfter(end)) {
                dates.add(schedule.getDate());
            }
        }

        return dates;
    }

    private Set<java.time.DayOfWeek> parseDayCodes(String daysOfWeek) {
        if (daysOfWeek == null || daysOfWeek.isBlank()) {
            return EnumSet.noneOf(java.time.DayOfWeek.class);
        }
        Set<java.time.DayOfWeek> parsed = EnumSet.noneOf(java.time.DayOfWeek.class);
        for (String code : daysOfWeek.split(",")) {
            try {
                parsed.add(dayCodeToDayOfWeek(code.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid day code in schedule snapshot: {}", code);
            }
        }
        return parsed;
    }

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
}
