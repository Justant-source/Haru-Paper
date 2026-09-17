package com.harupaper.server.widget.stock;

/** 심볼 자체는 유효할 수 있으나 조회에 실패한 경우(네트워크·파싱·5xx 등). */
public class StockQuoteException extends RuntimeException {

    public StockQuoteException(String message) {
        super(message);
    }

    public StockQuoteException(String message, Throwable cause) {
        super(message, cause);
    }
}
