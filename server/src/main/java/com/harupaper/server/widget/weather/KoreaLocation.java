package com.harupaper.server.widget.weather;

/**
 * 대한민국 시·군·구 위치 한 항목(server/src/main/resources/widgets/korea-locations.json 원본).
 * 시·도 자체 항목은 name이 빈 문자열이다(예: "경기도" 자체를 고르는 항목).
 *
 * @param sido   시·도 이름(예: "경기도")
 * @param name   시·군·구(+일반구) 이름(예: "성남시 분당구"). 시·도 자체 항목이면 빈 문자열
 * @param lat    위도(소수 4자리)
 * @param lon    경도(소수 4자리)
 * @param manual Nominatim 조회가 끝까지 실패해 수동으로 채운 좌표면 true
 */
public record KoreaLocation(String sido, String name, double lat, double lon, boolean manual) {

    /** GET /api/widgets/locations 응답·PropField.KOREA_LOCATION 값에 쓰는 표시 라벨 */
    public String label() {
        return name == null || name.isBlank() ? sido : sido + " " + name;
    }
}
