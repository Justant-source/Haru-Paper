package com.harupaper.server.device;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.common.security.TokenHasher;
import com.harupaper.server.common.time.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 기기 관리 API for web app (사용자 인증 필요).
 * POST /api/devices/me/token - 토큰 발급/재발급
 * POST /api/devices/pairing-codes - 페어링 코드 생성
 * GET /api/devices/me - 기기 정보 조회
 * PATCH /api/devices/me - 기기 이름 변경
 * (페어링 수행은 POST /api/device/pair, DeviceController에 있음)
 * (docs/server/api.md 5절 "기기 관리")
 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@Slf4j
public class DeviceManagementController {

    private final DeviceRepository deviceRepository;
    private final PairingCodeRepository pairingCodeRepository;

    /**
     * POST /api/devices/me/token
     * 로그인 필요. 토큰 발급/재발급. 기존 기기가 있으면 토큰만 갱신, 없으면 새 기기 생성.
     */
    @PostMapping("/me/token")
    public ResponseEntity<DeviceManagementDto.TokenIssueResponse> issueToken(
            @AuthenticationPrincipal UserPrincipal principal) {
        String userId = principal.userId();

        // 기존 기기 조회
        Optional<Device> existing = deviceRepository.findByOwnerUserId(userId);

        Device device;
        if (existing.isPresent()) {
            device = existing.get();
        } else {
            // 새 기기 생성
            device = Device.builder()
                    .id(UUID.randomUUID().toString())
                    .ownerUserId(userId)
                    .name("내 프린터")  // 기본값
                    .paperStateManual(false)
                    .createdAt(Instant.now())
                    .build();
        }

        // 토큰 생성 및 저장
        String token = TokenHasher.generateToken();
        String tokenHash = TokenHasher.sha256Hex(token);
        Instant now = Instant.now();

        device.setTokenHash(tokenHash);
        device.setTokenIssuedAt(now);

        device = deviceRepository.save(device);

        // 응답: 원문 토큰은 이번만 보여줌
        DeviceManagementDto.TokenIssueResponse response = new DeviceManagementDto.TokenIssueResponse(
                device.getId(),
                token,
                TimeUtils.toIso8601(now)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/devices/pairing-codes
     * 로그인 필요. 8자 일회용 페어링 코드 생성.
     * 기본값: 대문자+숫자, I/O/0/1 제외, 10분 유효
     */
    @PostMapping("/pairing-codes")
    public ResponseEntity<DeviceManagementDto.PairingCodeResponse> createPairingCode(
            @AuthenticationPrincipal UserPrincipal principal) {
        String userId = principal.userId();

        // 8자 코드 생성 (혼동 가능한 문자 제외)
        String code = generatePairingCode();

        PairingCode pairingCode = PairingCode.builder()
                .code(code)
                .userId(userId)
                .expiresAt(Instant.now().plusSeconds(600))  // 10분
                .createdAt(Instant.now())
                .build();

        pairingCode = pairingCodeRepository.save(pairingCode);

        DeviceManagementDto.PairingCodeResponse response = new DeviceManagementDto.PairingCodeResponse(
                code,
                TimeUtils.toIso8601(pairingCode.getExpiresAt())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/devices/me
     * 로그인 필요. 현재 사용자의 기기 정보 조회.
     */
    @GetMapping("/me")
    public ResponseEntity<DeviceManagementDto.GetDeviceInfoResponse> getDeviceInfo(
            @AuthenticationPrincipal UserPrincipal principal) {
        String userId = principal.userId();

        Optional<Device> deviceOpt = deviceRepository.findByOwnerUserId(userId);
        if (deviceOpt.isEmpty()) {
            // 페어링 전 → 404 대신 빈 응답
            return ResponseEntity.ok(new DeviceManagementDto.GetDeviceInfoResponse(
                    null, null, false
            ));
        }

        Device device = deviceOpt.get();
        DeviceManagementDto.GetDeviceInfoResponse response = new DeviceManagementDto.GetDeviceInfoResponse(
                device.getId(),
                device.getName(),
                device.getTokenIssuedAt() != null
        );

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/devices/me
     * 로그인 필요. 기기 이름 변경.
     */
    @PatchMapping("/me")
    public ResponseEntity<DeviceManagementDto.GetDeviceInfoResponse> updateDeviceInfo(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody DeviceManagementDto.UpdateDeviceInfoRequest request) {
        String userId = principal.userId();

        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ValidationException("name is required", List.of(
                    new ValidationException.FieldError("name", "must not be blank")
            ));
        }

        Device device = deviceRepository.findByOwnerUserId(userId)
                .orElseThrow(() -> new ValidationException("Device not paired", List.of(
                        new ValidationException.FieldError("device", "not paired yet")
                )));

        device.setName(request.name().trim());
        device = deviceRepository.save(device);

        DeviceManagementDto.GetDeviceInfoResponse response = new DeviceManagementDto.GetDeviceInfoResponse(
                device.getId(),
                device.getName(),
                device.getTokenIssuedAt() != null
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 8자 페어링 코드 생성: 대문자+숫자, I/O/0/1 제외
     */
    private String generatePairingCode() {
        // 혼동 가능한 문자 제외: I, O, 0, 1
        String charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 8; i++) {
            sb.append(charset.charAt(random.nextInt(charset.length())));
        }
        return sb.toString();
    }
}
