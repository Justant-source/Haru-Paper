package com.harupaper.server.render;

import com.harupaper.server.device.DeviceWakeNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;

/**
 * 포맷·예약·설정·프린터 프로필이 바뀌면 5분 주기를 기다리지 않고 렌더 스캔을 돌린다
 * (docs/server/rendering.md 4절). 커밋 이후에 돌려서 방금 저장한 행을 보게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenderScanTrigger {

    private final RenderScheduler renderScheduler;
    private final DeviceWakeNotifier deviceWakeNotifier;

    /**
     * 소유자를 특정할 수 없는(또는 깨울 필요가 없는) 호출용. DeviceSyncService.java:92처럼 poll
     * 처리 중에 부르는 경우가 그렇다 — 그 시점엔 깨울 대상이 이미 poll 중인 그 기기라 의미가 없다.
     */
    public void requestScan() {
        runScan(null);
    }

    /**
     * 예약을 새로 만들면 렌더는 이 비동기 스캔이 만든다. 스캔 전에 깨우면 Pi가 렌더 없는
     * 스냅샷을 받고 정작 렌더는 다음 정규 폴링까지 못 받는다 — 그래서 스캔이 끝난 뒤에 깨운다.
     */
    public void requestScan(String ownerUserId) {
        runScan(ownerUserId);
    }

    private void runScan(String ownerUserId) {
        Runnable scan = () -> {
            try {
                renderScheduler.scanAndRender();
            } catch (Exception e) {
                log.error("Immediate render scan failed", e);
            } finally {
                // scanAndRender()가 예외를 던져도 깨우기는 실행돼야 한다 — 예약 행은 이미
                // 커밋됐고 스냅샷 해시는 이미 바뀌었으므로, 다음 poll에서 Pi가 새 스냅샷을
                // 받아갈 수 있어야 한다.
                if (ownerUserId != null) {
                    deviceWakeNotifier.wake(ownerUserId, "snapshot");
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(scan);
                }
            });
        } else {
            CompletableFuture.runAsync(scan);
        }
    }
}
