package com.harupaper.server.widget.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Yahoo Finance에 1회 부딪혀 보는 일회성 확인(.temp/07 6절, S3 최종 보고 6번 항목).
 * 일반 테스트 스위트에 포함하지 않는다 — 네트워크가 있을 때 사람이 직접 켜서 돌린다.
 *
 * {@code STOCK_REAL_API=1 ./gradlew test --tests '*YahooChartStockQuoteProviderRealApiCheck*'}
 */
@EnabledIfEnvironmentVariable(named = "STOCK_REAL_API", matches = "1")
class YahooChartStockQuoteProviderRealApiCheck {

    @Test
    @DisplayName("AAPL 14일 실측 + 없는 티커 ZZZZQ 실측")
    void hitsRealYahooApiOnce() {
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider();

        StockSeries aapl = provider.getDaily("AAPL", 14);
        System.out.println("[실측] AAPL 봉 개수=" + aapl.candles().size()
                + ", 마지막 종가=" + aapl.candles().get(aapl.candles().size() - 1).close()
                + ", previousClose=" + aapl.previousClose()
                + ", shortName=" + aapl.shortName()
                + ", currency=" + aapl.currency());
        assertFalse(aapl.candles().isEmpty());
        assertTrue(aapl.candles().size() <= 14);

        Exception ex = assertThrows(SymbolNotFoundException.class, () -> provider.getDaily("ZZZZQ", 14));
        System.out.println("[실측] ZZZZQ 예외 종류=" + ex.getClass().getSimpleName() + ", 메시지=" + ex.getMessage());
    }
}
