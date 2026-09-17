package com.harupaper.server.format;

import com.harupaper.server.widget.WidgetInstance;

import java.util.List;

/**
 * 포맷 문서 v3 (원본: docs/server/format-schema.md, .temp/07-위젯그리드-작업지시서.md).
 * assets는 가져오기/내보내기 요청에서만 별도로 다루고, 이 record에는 포함하지 않는다.
 *
 * schemaVersion 3: 포맷 = 위젯의 순서 목록(widgets). 배치는 4열 CSS 그리드가 자동으로 한다
 * (grid-auto-flow: row dense). 옛 v1(blocks)·v2(rows/slots) 저장본은
 * FormatDocumentSupport.readDocument()에서 읽을 때 자동으로 v3로 up-convert된다.
 */
public record FormatDocument(
        int schemaVersion,
        FormatMeta meta,
        FormatStyle style,
        List<WidgetInstance> widgets
) {}
