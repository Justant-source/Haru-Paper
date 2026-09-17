package com.harupaper.server.device;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;

/**
 * 명령·예약·용지 상태 등이 바뀌었을 때 DeviceEventRegistry를 통해 Pi의 SSE 연결을 깨운다.
 *
 * 항상 트랜잭션 커밋 이후에 깨운다(RenderScanTrigger와 같은 관용구, docs/architecture.md 4.3절):
 * 커밋 전에 깨우면 Pi가 아직 안 보이는(아직 DB에 반영되지 않은) 데이터를 읽고 헛걸음한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceWakeNotifier {

    private final DeviceEventRegistry deviceEventRegistry;

    public void wake(String ownerUserId, String reason) {
        Runnable send = () -> {
            try {
                deviceEventRegistry.wake(ownerUserId, reason);
            } catch (Exception e) {
                log.warn("Device wake failed", e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(send);
                }
            });
        } else {
            CompletableFuture.runAsync(send);
        }
    }
}
