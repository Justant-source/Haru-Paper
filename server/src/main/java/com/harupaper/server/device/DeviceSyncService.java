package com.harupaper.server.device;

import com.harupaper.server.command.Command;
import com.harupaper.server.command.CommandRepository;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.render.Render;
import com.harupaper.server.render.RenderRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
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
    private final ResultRepository resultRepository;
    private final ObjectMapper objectMapper;
    private final SnapshotHashCalculator snapshotHashCalculator;

    @Value("${haru.poll-interval-sec:30}")
    private Integer pollIntervalSec;

    @Value("${haru.files-dir:/data/haru-files}")
    private String filesDir;

    /**
     * POST /api/device/poll 처리
     * (docs/server/api.md 6절 "poll 처리 순서")
     */
    @Transactional
    public DeviceDto.PollResponse processPoll(DeviceDto.PollRequest request) {
        Instant now = Instant.now();

        Device device = deviceRepository.findById(1).orElseGet(() ->
                Device.builder().id(1).paperStateManual(false).build()
        );

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

        // 2. 프로필이 바뀌었으면 렌더 스케줄러 트리거 (생략 - Rendering 에이전트가 알아서 함)

        // 3. 현재 스냅샷의 snapshotHash 계산 → snapshotChanged 확인
        DeviceDto.SnapshotResponse snapshot = buildSnapshot();
        String currentHash = snapshotHashCalculator.calculate(snapshot.schedules(), snapshot.renders());
        boolean snapshotChanged = !currentHash.equals(request.snapshotHash());

        // 4. 만료 처리
        Instant expireThreshold = now.minusSeconds(600); // 10분
        List<Command> expiredCommands = commandRepository.findAllByStatusInAndCreatedAtBefore(
                List.of("pending", "delivered"), expireThreshold);
        for (Command cmd : expiredCommands) {
            cmd.setStatus("expired");
        }
        if (!expiredCommands.isEmpty()) {
            commandRepository.saveAll(expiredCommands);
        }

        // 5. 남은 pending+delivered 명령을 commands[]로 실어 보내고, pending은 delivered로 상태 변경
        List<Command> activeCommands = commandRepository.findAllByStatusIn(List.of("pending", "delivered"));
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
    public DeviceDto.SnapshotResponse buildSnapshot() {
        List<Schedule> allSchedules = scheduleRepository.findAll();
        List<DeviceDto.ScheduleDto> scheduleDtos = allSchedules.stream()
                .map(this::toScheduleDto)
                .sorted(Comparator.comparing(DeviceDto.ScheduleDto::id))
                .collect(Collectors.toList());

        // 켜진 예약이 참조하는 포맷들의 렌더를 포함
        List<Schedule> enabledSchedules = scheduleRepository.findAllByEnabledTrue();
        Set<String> enabledFormatIds = enabledSchedules.stream()
                .map(Schedule::getFormatId)
                .collect(Collectors.toSet());

        // 현재 profileKey 계산
        PrinterProfile profile = getCurrentProfile();
        String profileKey = profile.profileKey();

        // 36시간 안의 occurrence 날짜 범위 (간단히 구현: 오늘과 내일)
        LocalDate today = TimeUtils.todayInKST();
        LocalDate tomorrow = today.plusDays(1);

        List<DeviceDto.RenderDto> renderDtos = new ArrayList<>();
        Set<String> addedRenderIds = new HashSet<>();

        for (String formatId : enabledFormatIds) {
            // 지금부터 36시간 안 occurrence의 (formatId, targetDate)별 최신 렌더
            for (LocalDate targetDate : List.of(today, tomorrow)) {
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
    public DeviceDto.ResultsResponse processResults(DeviceDto.ResultsRequest request) {
        List<String> accepted = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        Instant now = Instant.now();

        for (DeviceDto.ResultDto resultDto : request.results()) {
            boolean exists = resultRepository.existsById(resultDto.resultId());

            if (exists) {
                // 중복 처리: 기존 결과 유지, duplicates 목록에 추가
                duplicates.add(resultDto.resultId());
                // 내용이 다르면 경고 로그 (이 코드에서는 생략)
            } else {
                // 새로운 결과 저장
                Result result = Result.builder()
                        .id(resultDto.resultId())
                        .occurrenceKey(resultDto.occurrenceKey())
                        .commandId(resultDto.commandId())
                        .formatId(resultDto.formatId())
                        .renderId(resultDto.renderId())
                        .status(resultDto.status())
                        .detail(resultDto.detail())
                        .scheduledAt(parseIso8601(resultDto.scheduledAt()))
                        .executedAt(parseIso8601(resultDto.executedAt()))
                        .receivedAt(now)
                        .build();

                resultRepository.save(result);
                accepted.add(resultDto.resultId());

                // commandId가 있으면 해당 명령을 done으로
                if (resultDto.commandId() != null) {
                    Optional<Command> cmdOpt = commandRepository.findById(resultDto.commandId());
                    if (cmdOpt.isPresent()) {
                        Command cmd = cmdOpt.get();
                        cmd.setStatus("done");
                        cmd.setCompletedAt(now);
                        commandRepository.save(cmd);
                    }
                }

                // manual_flag 정책에서 실패 결과 처리
                Device device = deviceRepository.findById(1).orElse(null);
                if (device != null && "manual_flag".equals(device.getPaperPolicy())) {
                    if ("failed".equals(resultDto.status()) || "skipped_printer_offline".equals(resultDto.status())) {
                        device.setPaperStateManual(false);
                        device.setPaperStateUpdatedAt(now);
                        device.setPaperStateUpdatedBy("server");
                        deviceRepository.save(device);
                    }
                }
            }
        }

        return new DeviceDto.ResultsResponse(accepted, duplicates);
    }

    /**
     * 프린터 프로필 조회 (현재 device 테이블의 값)
     */
    private PrinterProfile getCurrentProfile() {
        Device device = deviceRepository.findById(1).orElse(null);
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
        return new DeviceDto.RenderDto(
                r.getId(),
                r.getFormatId(),
                r.getTargetDate().toString(),
                r.getSha256(),
                r.getWidthPx(),
                TimeUtils.toIso8601(r.getRenderedAt()),
                "/api/device/renders/" + r.getId() + ".png"
        );
    }

    /**
     * ISO-8601 문자열을 Instant로 파싱 (null 안전)
     */
    private Instant parseIso8601(String iso8601) {
        if (iso8601 == null || iso8601.isBlank()) {
            return null;
        }
        try {
            // ISO-8601 파싱: "2026-09-14T07:00:00+09:00"
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                    .parse(iso8601, java.time.Instant::from);
        } catch (Exception e) {
            log.warn("Failed to parse ISO-8601 timestamp: {}", iso8601, e);
            return null;
        }
    }
}
