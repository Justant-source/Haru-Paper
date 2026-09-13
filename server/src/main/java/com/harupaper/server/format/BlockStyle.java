package com.harupaper.server.format;

/** 블록 스타일 화이트리스트 (format-schema.md 4.2절). 여기 없는 키는 422로 거부한다. */
public record BlockStyle(String align, Double fontSizePt, Boolean bold, Double marginTopMm, Double marginBottomMm) {}
