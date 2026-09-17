package com.harupaper.server.widget.stock;

import java.time.Instant;
import java.util.List;

/**
 * 종목 하나의 일봉 조회 결과.
 *
 * @param symbol        Yahoo가 돌려준 정규화 심볼(요청 심볼과 같아야 정상)
 * @param shortName     회사명(짧은 표기). 못 받으면 symbol과 같은 값
 * @param currency      통화 코드(USD 등)
 * @param candles       요청한 days만큼(혹은 그보다 적게) 자른 일봉 목록, 날짜 오름차순
 * @param previousClose 마지막 봉의 전 거래일 종가(등락 계산용). 봉이 2개 미만이면 null —
 *                      "candles 첫 봉 직전"이 아니라 항상 "마지막 봉 기준"이다(.temp/07 5.2절)
 * @param fetchedAt     이 값을 실제로 받아온 시각(폴백 캐시를 쓴 경우 그 캐시가 받아온 시각)
 * @param stale         true면 실시간 조회 실패로 72시간 이내의 직전 캐시를 돌려준 것
 */
public record StockSeries(
        String symbol,
        String shortName,
        String currency,
        List<Candle> candles,
        Double previousClose,
        Instant fetchedAt,
        boolean stale
) {
    public StockSeries {
        candles = candles == null ? List.of() : List.copyOf(candles);
    }
}
