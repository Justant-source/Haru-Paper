package com.harupaper.server.widget.stock;

/** 존재하지 않는 티커(Yahoo가 404 또는 {@code chart.error.code="Not Found"}로 응답). */
public class SymbolNotFoundException extends StockQuoteException {

    public SymbolNotFoundException(String symbol) {
        super("티커를 찾을 수 없습니다: " + symbol);
    }
}
