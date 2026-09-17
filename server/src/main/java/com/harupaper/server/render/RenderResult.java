package com.harupaper.server.render;

/** pbmSha256은 PBM이 아직 없는 옛 렌더(V3 마이그레이션 이전)일 때 null일 수 있다. */
public record RenderResult(String renderId, byte[] pngBytes, String sha256, int widthPx, int heightPx, String pbmSha256) {}
