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

    @Override
    public PrinterProfile getCurrentProfile() {
        // 폴백: 아무 기기나 하나 (RenderScheduler가 호출할 때, 여러 기기가 있으면
        // 이 메서드는 각각을 순회해야 하므로 3번 개선에서 없어진다)
        Device device = deviceRepository.findAll().stream().findFirst().orElse(null);

        if (device == null || device.getPrinterProfile() == null || device.getPrinterProfile().isBlank()) {
            log.debug("Device printer profile not found or empty, using DEFAULT");
            return PrinterProfile.DEFAULT;
        }

        try {
            return objectMapper.readValue(device.getPrinterProfile(), PrinterProfile.class);
        } catch (Exception e) {
            log.error("Failed to deserialize printer profile from device, using DEFAULT", e);
            return PrinterProfile.DEFAULT;
        }
    }

    /**
     * 특정 사용자의 기기 프로필을 가져온다 (렌더링에서 소유자별로 호출).
     */
    @Override
    public PrinterProfile getCurrentProfile(String ownerUserId) {
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
