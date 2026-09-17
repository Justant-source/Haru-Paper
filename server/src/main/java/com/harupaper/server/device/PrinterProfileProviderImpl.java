package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PrinterProfileProvider 구현.
 * device 테이블의 printer_profile(JSON 문자열)을 읽어 PrinterProfile record로 역직렬화한다.
 * 없으면 PrinterProfile.DEFAULT를 반환한다 (docs/server/api.md 4.1절).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrinterProfileProviderImpl implements PrinterProfileProvider {

    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    /**
     * 특정 사용자의 기기 프로필을 가져온다 (렌더링·정리 스케줄러가 소유자별로 호출).
     * ownerUserId가 null이면(레거시 리소스, claim-legacy 전이라 소유자를 모름) 어떤 기기와도
     * 연결 지을 수 없으므로 DEFAULT로 폴백한다 — deviceRepository.findByOwnerUserId(null)의
     * null 동치 비교 의미론에 기대지 않고 여기서 먼저 걸러낸다.
     */
    @Override
    public PrinterProfile getCurrentProfile(String ownerUserId) {
        if (ownerUserId == null) {
            log.debug("ownerUserId is null (unclaimed legacy resource), using DEFAULT");
            return PrinterProfile.DEFAULT;
        }

        Device device = deviceRepository.findByOwnerUserId(ownerUserId).orElse(null);

        if (device == null || device.getPrinterProfile() == null || device.getPrinterProfile().isBlank()) {
            log.debug("Device printer profile not found for user {}, using DEFAULT", ownerUserId);
            return PrinterProfile.DEFAULT;
        }

        try {
            return objectMapper.readValue(device.getPrinterProfile(), PrinterProfile.class);
        } catch (Exception e) {
            log.error("Failed to deserialize printer profile for user {}, using DEFAULT", ownerUserId, e);
            return PrinterProfile.DEFAULT;
        }
    }
}
