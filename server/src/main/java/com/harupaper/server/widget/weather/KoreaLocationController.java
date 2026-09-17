package com.harupaper.server.widget.weather;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /api/widgets/locations — weather 위젯 설정 화면의 "위치 선택기"가 쓰는 대한민국 시·군·구 목록.
 * (.temp/07-위젯그리드-작업지시서.md 5.3절)
 *
 * 인증은 SecurityConfig의 기존 /api/** 규칙에 맡긴다(이 컨트롤러는 직접 401을 내리지 않는다).
 */
@RestController
@RequestMapping("/api/widgets/locations")
@RequiredArgsConstructor
public class KoreaLocationController {

    private final KoreaLocations koreaLocations;

    @GetMapping
    public List<LocationDto> list() {
        return koreaLocations.all().stream()
                .map(loc -> new LocationDto(loc.sido(), loc.name(), loc.label(), loc.lat(), loc.lon()))
                .toList();
    }

    public record LocationDto(String sido, String name, String label, double lat, double lon) {
    }
}
