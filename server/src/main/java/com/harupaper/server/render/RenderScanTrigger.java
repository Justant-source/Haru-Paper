package com.harupaper.server.render;

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

    public void requestScan() {
        Runnable scan = () -> {
            try {
                renderScheduler.scanAndRender();
            } catch (Exception e) {
                log.error("Immediate render scan failed", e);
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
