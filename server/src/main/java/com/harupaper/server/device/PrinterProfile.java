package com.harupaper.server.device;

/**
 * Pi가 poll로 보고하는 프린터 프로필. 서버는 이 값으로만 렌더 폭을 정한다
 * (docs/architecture.md 3.5절). m832 프로토콜 상수는 여기 없다 — 표시용 model 문자열뿐이다.
 */
public record PrinterProfile(String model, int dpi, int paperWidthMm, int printableWidthPx) {

    /** Pi가 한 번도 poll하지 않았을 때 쓰는 기본 프로필 (docs/server/api.md 4.1절, [기본값]). */
    public static final PrinterProfile DEFAULT = new PrinterProfile("m832", 300, 110, 1300);

    /** {model}-{dpi}-{paperWidthMm}-{printableWidthPx} (docs/architecture.md 3.4절) */
    public String profileKey() {
        return model + "-" + dpi + "-" + paperWidthMm + "-" + printableWidthPx;
    }
}
