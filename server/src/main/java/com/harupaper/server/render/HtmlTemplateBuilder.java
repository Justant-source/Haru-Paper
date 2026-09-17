package com.harupaper.server.render;

import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.format.FormatDocument;
import com.harupaper.server.format.FormatStyle;
import com.harupaper.server.widget.GridSpec;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPageBuilder;
import com.harupaper.server.widget.WidgetRegistry;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FormatDocument(v3, 위젯 그리드)를 HTML로 변환한다(docs/server/rendering.md 2절,
 * .temp/07-위젯그리드-작업지시서.md 4·6절). 위젯 각각의 HTML은 WidgetRegistry에 등록된
 * Widget 구현이 그리고, 이 클래스는 그 조각들을 WidgetPageBuilder로 한 장의 그리드 페이지로
 * 조립하는 역할만 한다 — 날씨·에셋 같은 위젯별 데이터 조회는 각 Widget 구현(및 그 하위
 * 패키지)으로 옮겨졌다.
 */
@Slf4j
@Component
public class HtmlTemplateBuilder {

    private final WidgetRegistry widgetRegistry;

    public HtmlTemplateBuilder(WidgetRegistry widgetRegistry) {
        this.widgetRegistry = widgetRegistry;
    }

    /**
     * FormatDocument을 HTML로 변환한다.
     *
     * profile은 호출자(RenderServiceImpl)가 이미 구한 값을 그대로 받는다 — 여기서 다시
     * PrinterProfileProvider를 부르면 호출자가 쓴 프로필(소유자별)과 어긋날 수 있다
     * ("아무 기기나 하나" 폴백 제거, 2026-09-17).
     *
     * @param ownerUserId 포맷 소유자. 레거시 포맷이면 null일 수 있다(WidgetRenderContext로 그대로 전달)
     */
    public String buildHtml(FormatDocument document, LocalDate targetDate, PrinterProfile profile, String ownerUserId) {
        // 저장된 포맷은 FormatService.normalizeDocument가 이미 채워 왔지만, 즉석 미리보기
        // (POST /api/formats/preview)는 저장을 거치지 않고 바로 여기로 오므로 다시 한번
        // 필드별 기본값을 채운다 — {"style": {}}처럼 부분적으로 빈 입력에서도 안전해야 한다.
        FormatStyle style = FormatStyle.withDefaults(document.style());
        int contentWidthPx = WidgetPageBuilder.contentWidthPx(style, profile);

        List<WidgetInstance> widgets = document.widgets() != null ? document.widgets() : List.of();
        List<WidgetPageBuilder.Cell> cells = new ArrayList<>(widgets.size());
        for (WidgetInstance instance : widgets) {
            cells.add(renderCell(instance, profile, targetDate, ownerUserId, style, contentWidthPx));
        }

        return WidgetPageBuilder.build(style, profile, cells);
    }

    /**
     * 위젯 1개를 그린다. 등록되지 않은 type이거나 렌더 도중 예외가 나도 인쇄 전체를 막지 않고
     * errorBox로 대체한다(Widget.java 구현 규칙 ②, .temp/07 4절).
     */
    private WidgetPageBuilder.Cell renderCell(WidgetInstance instance, PrinterProfile profile, LocalDate targetDate,
                                               String ownerUserId, FormatStyle style, int contentWidthPx) {
        Optional<Widget> found = widgetRegistry.find(instance.type());
        if (found.isEmpty()) {
            // 등록 안 된 type(오래된 저장본 손상 등) — 4x1 자리에 오류 상자만 그린다.
            WidgetSize fallbackSize = WidgetSize.fixed(GridSpec.COLUMNS, 1, "알 수 없는 위젯");
            WidgetRenderContext ctx = buildContext(fallbackSize, profile, targetDate, ownerUserId, style, contentWidthPx);
            return new WidgetPageBuilder.Cell(fallbackSize, WidgetHtml.errorBox("알 수 없는 위젯", instance.type(), ctx));
        }

        Widget widget = found.get();
        WidgetDescriptor descriptor = widget.descriptor();
        WidgetSize size = descriptor.size(instance.size());
        if (size == null) {
            // 검증을 거치지 않은 옛 데이터 등으로 크기가 카탈로그에 없으면 기본 크기로 대체한다.
            size = descriptor.size(descriptor.defaultSize());
        }

        WidgetRenderContext ctx = buildContext(size, profile, targetDate, ownerUserId, style, contentWidthPx);
        String inner;
        try {
            inner = widget.renderHtml(instance, ctx);
        } catch (Exception e) {
            log.warn("위젯 렌더 실패: type={}, id={}", instance.type(), instance.id(), e);
            inner = WidgetHtml.errorBox(descriptor.name(), "위젯을 그리지 못했습니다", ctx);
        }
        return new WidgetPageBuilder.Cell(size, inner);
    }

    private WidgetRenderContext buildContext(WidgetSize size, PrinterProfile profile, LocalDate targetDate,
                                              String ownerUserId, FormatStyle style, int contentWidthPx) {
        int boxWidthPx = GridSpec.boxWidthPx(size.cols(), contentWidthPx, profile.dpi());
        Integer boxHeightPx = size.isAutoHeight() ? null : GridSpec.boxHeightPx(size.rows(), profile.dpi());
        return new WidgetRenderContext(targetDate, profile, ownerUserId, size, boxWidthPx, boxHeightPx, style.baseFontSizePt());
    }
}
