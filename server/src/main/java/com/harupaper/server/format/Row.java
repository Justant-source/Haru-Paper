package com.harupaper.server.format;

import java.util.List;

/**
 * 포맷 문서 v2의 행(row). 한 줄에 1~2개의 슬롯을 담는다.
 * row.id: 편집기 내부에서만 쓰는 uuid
 * slots: 1~2개
 */
public record Row(String id, List<Slot> slots) {}
