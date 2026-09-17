package com.harupaper.server.widget.stock;

import java.time.LocalDate;

/**
 * 일봉 캔들 하나. {@code date}는 거래소 현지(America/New_York 등) 날짜다 — KST가 아니다
 * (.temp/07-위젯그리드-작업지시서.md 5.2절, "봉 날짜는 exchangeTimezoneName 기준 현지 날짜로 변환").
 */
public record Candle(LocalDate date, double open, double high, double low, double close) {
}
