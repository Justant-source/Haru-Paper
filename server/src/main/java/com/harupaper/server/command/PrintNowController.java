package com.harupaper.server.command;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.device.DeviceRepository;
import com.harupaper.server.device.DeviceWakeNotifier;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.render.RenderResult;
import com.harupaper.server.render.RenderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * M6: PrintNowController: "지금 인쇄" 명령 생성 (사용자 인증 필수)
 * 소유권 스코핑: formatId와 deviceId는 현재 사용자의 것이어야 함
 * - POST /api/print-now {formatId, paperConfirmed} → 202 명령 생성
 *
 * 처리 흐름:
 * 1. formatId가 현재 사용자의 것인지 확인 (아니면 404)
 * 2. renderService.renderForCommand(formatId, 오늘 날짜) 호출
 * 3. Command 엔티티 생성: id=UUID, status="pending", createdAt=now, owner_user_id=현재사용자, device_id=사용자기기
 * 4. 202 응답
 *
 * 주의: paperConfirmed=false여도 거절하지 않는다 — 그대로 저장해 전달.
 * 용지 정책 판단은 Pi 몫 (unverified 정책이면 Pi가 skipped_no_paper로 기록).
 * 명령 만료 처리(10분)는 Device 에이전트가 poll 처리 중에 한다.
 */
@RestController
@RequestMapping("/api/print-now")
public class PrintNowController {
    private final FormatRepository formatRepository;
    private final CommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final RenderService renderService;
    private final DeviceWakeNotifier deviceWakeNotifier;

    public PrintNowController(
            FormatRepository formatRepository,
            CommandRepository commandRepository,
            DeviceRepository deviceRepository,
            RenderService renderService,
            DeviceWakeNotifier deviceWakeNotifier
    ) {
        this.formatRepository = formatRepository;
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.renderService = renderService;
        this.deviceWakeNotifier = deviceWakeNotifier;
    }

    /**
     * POST /api/print-now
     * {formatId, paperConfirmed} → 202 {commandId, formatId, renderId, paperConfirmed, status: "pending", createdAt}
     */
    @PostMapping
    public ResponseEntity<PrintNowResponseDto> printNow(
            @RequestBody PrintNowRequestDto request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();

        // 1. formatId 존재 및 소유권 확인 (없거나 소유자가 아니면 404)
        var formatOpt = formatRepository.findById(request.formatId());
        if (formatOpt.isEmpty() || !userId.equals(formatOpt.get().getOwnerUserId())) {
            throw new NotFoundException("Format not found: " + request.formatId());
        }

        // 2. 즉시 렌더 (targetDate = 오늘 KST, kind=command)
        RenderResult renderResult = renderService.renderForCommand(
                request.formatId(),
                TimeUtils.todayInKST()
        );

        // 3. Command 엔티티 생성
        String commandId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // 사용자의 기기 가져오기 (없으면 null로 두어도 됨 — 페어링 전)
        String deviceId = deviceRepository.findByOwnerUserId(userId)
                .map(device -> device.getId())
                .orElse(null);

        Command command = Command.builder()
                .id(commandId)
                .type("print_now")
                .formatId(request.formatId())
                .renderId(renderResult.renderId())
                .paperConfirmed(request.paperConfirmed() != null ? request.paperConfirmed() : false)
                .status("pending")
                .ownerUserId(userId)
                .deviceId(deviceId)
                .createdAt(now)
                .build();

        Command saved = commandRepository.save(command);
        deviceWakeNotifier.wake(userId, "command");

        // 4. 202 응답
        PrintNowResponseDto response = new PrintNowResponseDto(
                saved.getId(),
                saved.getFormatId(),
                saved.getRenderId(),
                saved.getPaperConfirmed(),
                saved.getStatus(),
                TimeUtils.toIso8601(saved.getCreatedAt())
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
