package com.harupaper.server.widget.stock;

/**
 * 증시 일봉 시세 출처 인터페이스. 지금은 미국 증시 티커만 다루지만,
 * 나중에 환율(KRW=X) 등으로 넓힐 수 있게 파라미터 이름을 symbol로 둔다(.temp/07 5.2절).
 */
public interface StockQuoteProvider {

    /**
     * @param symbol 대문자 티커(예: AAPL, BRK-B). 호출 전에 반드시 정규식으로 검증돼 있어야 한다
     * @param days   최근 N거래일
     * @throws SymbolNotFoundException 존재하지 않는 티커
     * @throws StockQuoteException     그 밖의 조회 실패
     */
    StockSeries getDaily(String symbol, int days);
}
