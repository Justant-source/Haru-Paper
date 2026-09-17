package com.harupaper.server.widget.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KoreaLocationController: GET /api/widgets/locations 응답의 label 조립.
 * 인증은 SecurityConfig(다른 담당) 몫이라 여기서는 컨트롤러 로직만 본다.
 */
@DisplayName("KoreaLocationController: 위치 목록 응답")
class KoreaLocationControllerTest {

    @Test
    @DisplayName("시·도 자체 항목은 label이 sido와 같고, 그 밖은 'sido name' 형태다")
    void listAssemblesLabels() {
        KoreaLocationController controller = new KoreaLocationController(new KoreaLocations());
        List<KoreaLocationController.LocationDto> list = controller.list();

        assertThat(list).isNotEmpty();

        KoreaLocationController.LocationDto sidoOnly = list.stream()
                .filter(dto -> dto.sido().equals("경기도") && dto.name().isEmpty())
                .findFirst().orElseThrow();
        assertThat(sidoOnly.label()).isEqualTo("경기도");

        KoreaLocationController.LocationDto withName = list.stream()
                .filter(dto -> dto.sido().equals("경기도") && dto.name().equals("성남시 분당구"))
                .findFirst().orElseThrow();
        assertThat(withName.label()).isEqualTo("경기도 성남시 분당구");
        assertThat(withName.lat()).isBetween(33.0, 39.0);
        assertThat(withName.lon()).isBetween(124.0, 132.0);
    }
}
