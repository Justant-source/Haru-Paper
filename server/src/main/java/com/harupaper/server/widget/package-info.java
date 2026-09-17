/**
 * 위젯 프레임워크 (docs/server/widgets.md, 포맷 스키마 v3).
 *
 * 포맷 = 4열 그리드에 놓인 위젯들의 순서 목록이다. 위젯 하나는 이 패키지의 {@link com.harupaper.server.widget.Widget}
 * 구현 1개(@Component)이고, 등록은 {@link com.harupaper.server.widget.WidgetRegistry}가 스프링 주입으로 자동 수집한다.
 * 새 위젯을 추가할 때 고칠 곳은 "새 패키지 1개"뿐이다 — 검증기·렌더러·앱 편집기는 descriptor만 보고 동작한다.
 */
package com.harupaper.server.widget;
