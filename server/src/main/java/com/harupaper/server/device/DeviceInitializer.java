package com.harupaper.server.device;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 device 테이블에 id=1 행이 없으면 생성한다.
 * (docs/server/data-model.md 3. device)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceInitializer implements ApplicationRunner {

    private final DeviceRepository deviceRepository;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (!deviceRepository.existsById(1)) {
            Device device = Device.builder()
                    .id(1)
                    .paperStateManual(false)
                    .build();
            deviceRepository.save(device);
            log.info("Initialized device table with id=1");
        }
    }
}
