package com.harupaper.server.widget.weather;

import com.harupaper.server.widget.PropField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KoreaLocations: 기동 시 실제 korea-locations.json을 로드해서 범위·중복·필수 항목을 검증한다.
 * (.temp/07-위젯그리드-작업지시서.md 5.3절 "검증" 절)
 */
@DisplayName("KoreaLocations: 대한민국 위치 목록 로드")
class KoreaLocationsTest {

    private KoreaLocations koreaLocations;

    @BeforeEach
    void setUp() {
        koreaLocations = new KoreaLocations();
    }

    @Test
    @DisplayName("약 260~300개 항목을 로드한다")
    void loadsExpectedCount() {
        List<KoreaLocation> all = koreaLocations.all();
        assertThat(all).hasSizeBetween(250, 320);
    }

    @Test
    @DisplayName("(sido, name) 쌍은 전부 유일하다")
    void noDuplicates() {
        Set<String> seen = new HashSet<>();
        for (KoreaLocation loc : koreaLocations.all()) {
            String key = loc.sido() + "::" + loc.name();
            assertThat(seen.add(key)).as("중복: %s", key).isTrue();
        }
    }

    @Test
    @DisplayName("모든 좌표가 대한민국 범위 안이다(PropField.KOREA_LAT/LON_MIN/MAX와 같은 범위)")
    void allWithinKoreaBounds() {
        for (KoreaLocation loc : koreaLocations.all()) {
            assertThat(loc.lat())
                    .as("%s 위도", loc.label())
                    .isBetween(PropField.KOREA_LAT_MIN, PropField.KOREA_LAT_MAX);
            assertThat(loc.lon())
                    .as("%s 경도", loc.label())
                    .isBetween(PropField.KOREA_LON_MIN, PropField.KOREA_LON_MAX);
        }
    }

    @Test
    @DisplayName("시·도 자체 항목(name 빈 문자열)이 17개 있다")
    void hasSeventeenSidoEntries() {
        long sidoOnly = koreaLocations.all().stream().filter(l -> l.name().isEmpty()).count();
        assertThat(sidoOnly).isEqualTo(17);
    }

    @Test
    @DisplayName("필수 항목이 존재한다(일반구·행정구역 개편·외곽 도서 포함)")
    void containsRequiredEntries() {
        List<KoreaLocation> all = koreaLocations.all();
        assertThat(all).anyMatch(l -> l.sido().equals("서울특별시") && l.name().equals("강남구"));
        assertThat(all).anyMatch(l -> l.sido().equals("경기도") && l.name().equals("성남시 분당구"));
        assertThat(all).anyMatch(l -> l.sido().equals("제주특별자치도") && l.name().equals("서귀포시"));
        assertThat(all).anyMatch(l -> l.sido().equals("경상북도") && l.name().equals("울릉군"));
        assertThat(all).anyMatch(l -> l.sido().equals("인천광역시") && l.name().equals("옹진군"));
        // 2026-09-18 기준 최신 행정구역: 대구 군위군 편입, 화성 일반구, 인천 개편, 부천 일반구 부활
        assertThat(all).anyMatch(l -> l.sido().equals("대구광역시") && l.name().equals("군위군"));
        assertThat(all).anyMatch(l -> l.sido().equals("경기도") && l.name().equals("화성시 동탄구"));
        assertThat(all).anyMatch(l -> l.sido().equals("인천광역시") && l.name().equals("검단구"));
        assertThat(all).anyMatch(l -> l.sido().equals("경기도") && l.name().equals("부천시 원미구"));
    }

    @Test
    @DisplayName("label()은 sido와 name을 공백으로 잇고, name이 없으면 sido만이다")
    void labelAssembly() {
        KoreaLocation sidoOnly = new KoreaLocation("경기도", "", 37.4, 127.5, false);
        assertThat(sidoOnly.label()).isEqualTo("경기도");
        KoreaLocation withName = new KoreaLocation("경기도", "성남시 분당구", 37.38, 127.12, false);
        assertThat(withName.label()).isEqualTo("경기도 성남시 분당구");
    }
}
