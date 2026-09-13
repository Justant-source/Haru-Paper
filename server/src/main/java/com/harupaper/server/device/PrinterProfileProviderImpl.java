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
        Device device = deviceRepository.findById(1).orElse(null);

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
}
