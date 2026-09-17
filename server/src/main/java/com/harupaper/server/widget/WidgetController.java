package com.harupaper.server.widget;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /api/widgets — 위젯 카탈로그(.temp/07-위젯그리드-작업지시서.md 6절).
 * 앱은 위젯 종류를 하드코딩하지 않고 이 응답만 보고 카탈로그·크기 선택·설정 폼을 그린다.
 * 인증은 SecurityConfig의 기존 /api/** 규칙에 맡긴다(이 컨트롤러는 별도 인증 검사를 하지 않는다).
 */
@RestController
@RequestMapping("/api/widgets")
public class WidgetController {

    private final WidgetRegistry widgetRegistry;

    public WidgetController(WidgetRegistry widgetRegistry) {
        this.widgetRegistry = widgetRegistry;
    }

    @GetMapping
    public WidgetCatalogResponse catalog() {
        // WidgetRegistry.all()은 등록 순서(스프링 빈 주입 순서)를 유지한다 — catalog=false 위젯도 포함한다.
        List<WidgetDescriptor> descriptors = widgetRegistry.all().stream()
                .map(Widget::descriptor)
                .toList();
        return new WidgetCatalogResponse(GridInfo.CURRENT, descriptors);
    }

    /** GridSpec 상수를 그대로 JSON으로 내려준다(.temp/07 2절 그리드 규격). */
    public record GridInfo(int columns, double rowUnitMm, double gapMm, int maxWidgets) {
        static final GridInfo CURRENT = new GridInfo(
                GridSpec.COLUMNS, GridSpec.ROW_UNIT_MM, GridSpec.GAP_MM, GridSpec.MAX_WIDGETS);
    }

    public record WidgetCatalogResponse(GridInfo grid, List<WidgetDescriptor> widgets) {}
}
