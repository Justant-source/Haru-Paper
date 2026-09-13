package com.harupaper.server.render;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Chromium PNG(RGB)를 8비트 그레이스케일 PNG로 변환한다 (docs/server/rendering.md 5절).
 * - 변환: RGB(A) → TYPE_BYTE_GRAY
 * - 폭이 정확히 printableWidthPx인지 검증 (다르면 500 에러)
 * - 투명 영역은 흰색으로 합성
 */
@Slf4j
@Component
public class GrayscaleConverter {

    private static final int MAX_HEIGHT_PX = 11811;  // 1000mm at 300dpi (docs/server/rendering.md 4절)

    /**
     * Chromium PNG를 8비트 그레이스케일 PNG로 변환한다.
     *
     * @param rgbPngBytes   Chromium이 반환한 PNG 바이트 (RGB)
     * @param expectedWidthPx 예상 너비(px) = printableWidthPx
     * @return 8비트 그레이스케일 PNG 바이트
     */
    public byte[] convertToGrayscale(byte[] rgbPngBytes, int expectedWidthPx) {
        try {
            // PNG 디코딩
            ByteArrayInputStream bais = new ByteArrayInputStream(rgbPngBytes);
            BufferedImage rgbImage = ImageIO.read(bais);

            if (rgbImage == null) {
                throw new RuntimeException("Failed to decode PNG image");
            }

            // 폭 검증
            if (rgbImage.getWidth() != expectedWidthPx) {
                throw new RuntimeException(
                        String.format("PNG width mismatch: expected=%d, actual=%d",
                                expectedWidthPx, rgbImage.getWidth()));
            }

            // 높이 검증
            if (rgbImage.getHeight() > MAX_HEIGHT_PX) {
                throw new RuntimeException(
                        String.format("PNG height exceeds maximum: max=%d, actual=%d",
                                MAX_HEIGHT_PX, rgbImage.getHeight()));
            }

            // 그레이스케일 변환
            BufferedImage grayscaleImage = new BufferedImage(
                    rgbImage.getWidth(),
                    rgbImage.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY);
            // TYPE_BYTE_GRAY의 ComponentColorModel은 setRGB(ARGB)를 받으면 자체 색공간 변환(감마 보정
            // 포함 색 관리 변환)을 다시 거쳐 우리가 계산한 휘도값과 다른 값을 만들어낸다
            // [확인됨: 순수 빨강(255,0,0)을 R=G=B=76으로 완전히 채운 ARGB로 setRGB해도 결과가 18로
            // 나오는 것을 픽셀 단위로 확인 — 흰/검정처럼 R=G=B인 입력이 우연히 항상 자기 값을 반환하는
            // 경우만 맞는 것처럼 보였을 뿐이다]. 색 모델을 거치지 않고 래스터에 계산한 휘도 바이트를
            // 직접 쓴다.
            java.awt.image.WritableRaster grayRaster = grayscaleImage.getRaster();

            // 픽셀 복사 (RGB → Gray 변환)
            for (int y = 0; y < rgbImage.getHeight(); y++) {
                for (int x = 0; x < rgbImage.getWidth(); x++) {
                    int rgb = rgbImage.getRGB(x, y);

                    // ARGB 분해
                    int alpha = (rgb >> 24) & 0xFF;
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    // 투명도 처리: 투명 영역은 흰색으로 합성
                    if (alpha < 255) {
                        float alphaF = alpha / 255.0f;
                        red = (int) (red * alphaF + 255 * (1 - alphaF));
                        green = (int) (green * alphaF + 255 * (1 - alphaF));
                        blue = (int) (blue * alphaF + 255 * (1 - alphaF));
                    }

                    // 그레이스케일 값 계산 (표준 가중치)
                    int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                    gray = Math.max(0, Math.min(255, gray));

                    grayRaster.setSample(x, y, 0, gray);
                }
            }

            // PNG 인코딩
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(grayscaleImage, "png", baos)) {
                throw new RuntimeException("Failed to encode grayscale PNG");
            }

            byte[] grayscalePng = baos.toByteArray();
            log.debug("Grayscale conversion complete: {}x{} → {} bytes",
                    grayscaleImage.getWidth(), grayscaleImage.getHeight(), grayscalePng.length);

            return grayscalePng;

        } catch (IOException e) {
            log.error("PNG conversion failed", e);
            throw new RuntimeException("Grayscale conversion failed: " + e.getMessage(), e);
        }
    }
}
