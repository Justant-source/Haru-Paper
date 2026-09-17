package com.harupaper.server.widget;

import com.harupaper.server.common.exception.ValidationException;

import java.util.List;
import java.util.Map;

/**
 * 위젯 1종. 구현체는 @Component로 등록하면 {@link WidgetRegistry}가 자동 수집한다.
 *
 * 구현 규칙 (docs/server/widgets.md):
 * <ol>
 *   <li>{@link #renderHtml}은 <b>서버가 조립한 HTML 조각</b>만 돌려준다. 외부에서 온 모든 문자열(사용자 입력·
 *       스크랩한 본문·API 응답)은 반드시 {@link WidgetHtml#escape}를 거친다. &lt;script&gt;, 외부 URL(img/src, css url())은
 *       금지다 — 렌더러는 JS를 끄고 네트워크를 전부 막는다(CLAUDE.md 절대 금지 6).</li>
 *   <li>외부 데이터 조회가 실패해도 예외를 던지지 않는다. 캐시된 직전 값이 있으면 그것으로 그리고,
 *       없으면 {@link WidgetHtml#errorBox}로 "가져오지 못했습니다"를 그린다. 인쇄 전체를 막지 않는다.</li>
 *   <li>감열 프린터는 흑백 1비트다. 회색·그라데이션을 쓰지 말고 검정(#000)/흰색만 쓴다.
 *       선 굵기는 3px 이상(300dpi에서 0.25mm), 글자는 7pt 이상.</li>
 *   <li>고정 크기(size.rows != null)면 내용이 boxWidthPx × boxHeightPx 안에 들어가야 한다(넘치면 잘린다).
 *       루트 요소는 width:100%; height:100%로 잡는다 — 옆 위젯 때문에 박스가 더 커질 수 있다.</li>
 *   <li>외부로 나가는 URL은 코드에 고정된 호스트뿐이다. 사용자 입력을 URL 호스트·경로에 그대로 넣지 않는다
 *       (정규식으로 검증한 값만 허용).</li>
 * </ol>
 */
public interface Widget {

    WidgetDescriptor descriptor();

    /**
     * descriptor.fields 기반의 일반 검증(타입·범위·필수·알 수 없는 키)이 끝난 뒤에 불린다.
     * 필드 사이의 관계처럼 스키마로 표현 못 하는 규칙만 여기서 본다. 기본은 아무것도 안 한다.
     *
     * @param path 오류 경로 접두사 (예: "widgets[2].props")
     */
    default void validateProps(Map<String, Object> props, String path, List<ValidationException.FieldError> errors) {
    }

    /** 위젯 박스 안쪽 HTML 조각을 돌려준다. 위 구현 규칙을 지킨다. */
    String renderHtml(WidgetInstance instance, WidgetRenderContext ctx);
}
