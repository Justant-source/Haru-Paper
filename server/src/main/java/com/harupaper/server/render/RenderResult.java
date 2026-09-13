package com.harupaper.server.render;

public record RenderResult(String renderId, byte[] pngBytes, String sha256, int widthPx, int heightPx) {}
