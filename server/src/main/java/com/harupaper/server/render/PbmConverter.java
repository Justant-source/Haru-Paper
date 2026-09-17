package com.harupaper.server.render;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 그레이스케일 PNG를 1-bpp PBM(P4, raw)으로 변환한다.
 * (docs/architecture.md 3.4절, 2026-09-17 사용자 승인 — 2단계 MCU 기기용)
 *
 * - PBM P4 표준 그대로: 1 = 검정, MSB-first, 각 행은 ceil(width/8) 바이트로 패딩
 * - 흑백 변환은 Floyd–Steinberg 오차확산 디더링 [기본값]
 * - 좌우 정렬 보정(h-offset)·헤드 폭 패딩·M832 헤더/꼬리 같은 프린터 상수는 여기 없다.
 *   그건 여전히 기기(Pi/ESP) 드라이버 몫이다(CLAUDE.md 구성요소 경계).
 */
@Slf4j
@Component
public class PbmConverter {

    /** 128 미만이면 검정으로 양자화한다 [기본값]. */
    private static final float THRESHOLD = 128f;

    /**
     * 8비트 그레이스케일 PNG(GrayscaleConverter 출력)를 PBM P4 바이트로 변환한다.
     *
     * @param grayscalePngBytes GrayscaleConverter.convertToGrayscale()의 출력
     * @return PBM P4 바이트("P4\n{width} {height}\n" 헤더 + raw 비트맵)
     */
    public byte[] convertToPbm(byte[] grayscalePngBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(grayscalePngBytes));
            if (image == null) {
                throw new RuntimeException("Failed to decode grayscale PNG for PBM conversion");
            }

            int width = image.getWidth();
            int height = image.getHeight();
            Raster raster = image.getRaster();

            // Floyd-Steinberg는 옆/아래 픽셀에 오차를 실수로 퍼뜨리므로 float 버퍼에서 계산한다.
            // TYPE_BYTE_GRAY는 밴드가 1개뿐이라 getSample(x, y, 0)이 휘도값(0~255)이다.
            float[][] gray = new float[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    gray[y][x] = raster.getSample(x, y, 0);
                }
            }

            // architecture.md 3.4: "각 행은 바이트 경계로 패딩(ceil(widthPx / 8) 바이트/행)"
            int rowStride = (width + 7) / 8;
            byte[] bitmap = new byte[rowStride * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float oldPixel = gray[y][x];
                    boolean black = oldPixel < THRESHOLD;
                    float newPixel = black ? 0f : 255f;
                    float error = oldPixel - newPixel;

                    if (black) {
                        // PBM 표준: 1 = 검정, MSB-first
                        bitmap[y * rowStride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                    }

                    // 표준 Floyd–Steinberg 분배: 오른쪽 7/16, 왼아래 3/16, 아래 5/16, 오른아래 1/16
                    if (x + 1 < width) {
                        gray[y][x + 1] += error * 7f / 16f;
                    }
                    if (y + 1 < height) {
                        if (x - 1 >= 0) {
                            gray[y + 1][x - 1] += error * 3f / 16f;
                        }
                        gray[y + 1][x] += error * 5f / 16f;
                        if (x + 1 < width) {
                            gray[y + 1][x + 1] += error * 1f / 16f;
                        }
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(64 + bitmap.length);
            String header = "P4\n" + width + " " + height + "\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(bitmap);

            byte[] pbmBytes = out.toByteArray();
            log.debug("PBM conversion complete: {}x{} -> {} bytes (row stride {})",
                    width, height, pbmBytes.length, rowStride);
            return pbmBytes;

        } catch (IOException e) {
            log.error("PBM conversion failed", e);
            throw new RuntimeException("PBM conversion failed: " + e.getMessage(), e);
        }
    }
}
