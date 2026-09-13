package com.harupaper.server.format;

import java.util.Map;

/**
 * 블록 공통 구조 (format-schema.md 4.1절). props는 타입별로 다르므로 Map으로 두고
 * 각 소비자(검증기·렌더러)가 type에 따라 필요한 키를 꺼내 쓴다.
 * type: "text" | "image" | "dateHeader" | "weather"
 */
public record Block(String type, Map<String, Object> props, BlockStyle style) {}
