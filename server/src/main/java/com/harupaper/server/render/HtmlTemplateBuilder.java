package com.harupaper.server.render;

import com.harupaper.server.asset.Asset;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.format.Block;
import com.harupaper.server.format.BlockStyle;
import com.harupaper.server.format.FormatDocument;
import com.harupaper.server.format.FormatStyle;
import com.harupaper.server.format.MarginMm;
import com.harupaper.server.format.Row;
import com.harupaper.server.format.Slot;
import com.harupaper.server.settings.WeatherLocation;
import com.harupaper.server.settings.WeatherLocationProvider;
import com.harupaper.server.weather.DailyWeather;
import com.harupaper.server.weather.WeatherProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * FormatDocument을 HTML로 변환한다 (docs/server/rendering.md 2절).
 * 사용자 HTML을 받지 않고 서버가 문자열을 조립하며 텍스트는 escape한다.
 */
@Slf4j
@Component
public class HtmlTemplateBuilder {

    private final AssetRepository assetRepository;
    private final WeatherProvider weatherProvider;
    private final WeatherLocationProvider weatherLocationProvider;
    private final String filesDir;

    public HtmlTemplateBuilder(AssetRepository assetRepository,
                               WeatherProvider weatherProvider,
                               WeatherLocationProvider weatherLocationProvider,
                               @Value("${haru.files-dir}") String filesDir) {
        this.assetRepository = assetRepository;
        this.weatherProvider = weatherProvider;
        this.weatherLocationProvider = weatherLocationProvider;
        this.filesDir = filesDir;
    }

    /**
     * FormatDocument을 HTML로 변환한다.
     * 파이프라인: 변수 치환 → 동적 데이터 조회(날씨) → 직접 HTML 생성
     *
     * profile은 호출자(RenderServiceImpl)가 이미 구한 값을 그대로 받는다 — 여기서 다시
     * PrinterProfileProvider를 부르면 호출자가 쓴 프로필(소유자별)과 어긋날 수 있다
     * ("아무 기기나 하나" 폴백 제거, 2026-09-17).
     */
    public String buildHtml(FormatDocument document, LocalDate targetDate, PrinterProfile profile) {
        // 저장된 포맷은 FormatService.normalizeDocument가 이미 채워 왔지만, 즉석 미리보기
        // (POST /api/formats/preview)는 저장을 거치지 않고 바로 여기로 오므로 다시 한번
        // 필드별 기본값을 채운다 — {"style": {}}처럼 부분적으로 빈 입력에서도 안전해야 한다.
        var style = FormatStyle.withDefaults(document.style());

        // 1. 변수 치환 (targetDate 기반)
        Map<String, String> dateVariables = buildDateVariables(targetDate);

        // 2. 동적 데이터 조회 (날씨)
        DailyWeather weather = null;
        if (document.rows() != null) {
            for (Row row : document.rows()) {
                if (row.slots() != null) {
                    for (Slot slot : row.slots()) {
                        Block block = slot.block();
                        if (block != null && "weather".equals(block.type())) {
                            weather = fetchWeather(targetDate);
                            break;
                        }
                    }
                    if (weather != null) break;
                }
            }
        }

        // 3. 직접 HTML 생성 (CSS는 인라인 스타일로)
        return generateHtml(document, style, profile, dateVariables, weather, targetDate);
    }

    /**
     * FormatDocument을 직접 HTML로 변환한다.
     * Thymeleaf 템플릿 엔진 없이 StringBuilder로 생성.
     */
    private String generateHtml(FormatDocument document, FormatStyle style,
                                com.harupaper.server.device.PrinterProfile profile,
                                Map<String, String> dateVariables, DailyWeather weather, LocalDate targetDate) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"ko\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <style>\n");
        html.append("    * { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("    body { ");
        html.append("font-family: '").append(escapeHtmlAttr(style.fontFamily())).append("', sans-serif; ");
        html.append("background: white; ");
        html.append("font-size: ").append(convertPtToPx(style.baseFontSizePt(), profile.dpi())).append("px; ");
        html.append("line-height: ").append(style.lineHeight()).append("; ");
        html.append("}\n");

        // 여백 계산
        int marginTopPx = (int) Math.round(style.marginMm().top() * profile.dpi() / 25.4);
        int marginRightPx = (int) Math.round(style.marginMm().right() * profile.dpi() / 25.4);
        int marginBottomPx = (int) Math.round(style.marginMm().bottom() * profile.dpi() / 25.4);
        int marginLeftPx = (int) Math.round(style.marginMm().left() * profile.dpi() / 25.4);

        html.append("    .container { ");
        html.append("padding: ").append(marginTopPx).append("px ").append(marginRightPx).append("px ")
            .append(marginBottomPx).append("px ").append(marginLeftPx).append("px; ");
        html.append("}\n");

        html.append("    .block { word-break: keep-all; overflow-wrap: anywhere; white-space: pre-wrap; }\n");
        html.append("    .divider { border: none; border-top: 1px solid black; margin: 5px 0; }\n");
        html.append("    .divider-dashed { border: none; border-top: 1px dashed black; margin: 5px 0; }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<div class=\"container\">\n");

        // 행→슬롯 이중 루프로 블록 렌더
        boolean isFirstRow = true;
        if (document.rows() != null) {
            for (int rowIdx = 0; rowIdx < document.rows().size(); rowIdx++) {
                Row row = document.rows().get(rowIdx);
                boolean isLastRow = (rowIdx == document.rows().size() - 1);

                // 행이 2개 슬롯인 경우 flex로 나란히 배치
                if (row.slots().size() == 2) {
                    html.append("<div style=\"display: flex; gap: 0;\">\n");
                    for (int slotIdx = 0; slotIdx < 2; slotIdx++) {
                        Slot slot = row.slots().get(slotIdx);
                        String width = slot.width();
                        double percentage = parseSlotWidthPercentage(width);
                        html.append("<div style=\"flex: 0 0 ").append(String.format("%.2f", percentage))
                            .append("%;\">\n");
                        if (slot.block() != null) {
                            html.append(renderBlock(slot.block(), profile, dateVariables, weather, targetDate));
                        }
                        html.append("</div>\n");
                    }
                    html.append("</div>\n");
                } else {
                    // 1개 슬롯인 경우
                    Slot slot = row.slots().get(0);
                    if (slot.block() != null) {
                        html.append(renderBlock(slot.block(), profile, dateVariables, weather, targetDate));
                    }
                }

                // 구분선 및 행 간격 (마지막 행 제외)
                if (!isLastRow) {
                    if ("line".equals(style.divider())) {
                        html.append("<hr class=\"divider\">\n");
                    } else if ("dashed".equals(style.divider())) {
                        html.append("<hr class=\"divider-dashed\">\n");
                    }

                    // 행 간격
                    int gapPx = (int) Math.round(style.blockGapMm() * profile.dpi() / 25.4);
                    html.append("<div style=\"height: ").append(gapPx).append("px;\"></div>\n");
                }

                isFirstRow = false;
            }
        }

        html.append("</div>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    /**
     * 블록을 HTML로 렌더한다.
     */
    private String renderBlock(Block block, com.harupaper.server.device.PrinterProfile profile,
                               Map<String, String> dateVariables, DailyWeather weather, LocalDate targetDate) {
        StringBuilder html = new StringBuilder();
        String type = block.type();
        BlockStyle blockStyle = block.style() != null ? block.style() : new BlockStyle(null, null, null, null, null);

        String styleAttr = buildBlockStyleAttr(blockStyle, profile);

        switch (type) {
            case "text":
                String text = (String) block.props().get("text");
                text = substituteTextVariables(text, dateVariables);
                html.append("<div class=\"block\" style=\"").append(styleAttr).append("\">\n");
                html.append("  <p>").append(escapeHtml(text)).append("</p>\n");
                html.append("</div>\n");
                break;

            case "image":
                String assetId = (String) block.props().get("assetId");
                Object widthPercent = block.props().get("widthPercent");
                int width = widthPercent != null ? ((Number) widthPercent).intValue() : 100;
                String imageDataUri = getImageDataUri(assetId);
                html.append("<div class=\"block\" style=\"").append(styleAttr)
                        .append(styleAttr.contains("text-align") ? "" : " text-align: center;").append("\">\n");
                html.append("  <img src=\"").append(escapeHtmlAttr(imageDataUri)).append("\" style=\"width: ")
                    .append(width).append("%;\">\n");
                html.append("</div>\n");
                break;

            case "dateHeader":
                String pattern = (String) block.props().getOrDefault("pattern", "YYYY년 M월 D일 dddd");
                String headerText = formatDateHeader(pattern, targetDate);
                html.append("<div class=\"block\" style=\"").append(styleAttr)
                        .append(styleAttr.contains("text-align") ? "" : " text-align: center;").append("\">\n");
                html.append("  <p>").append(escapeHtml(headerText)).append("</p>\n");
                html.append("</div>\n");
                break;

            case "weather":
                if (weather != null) {
                    html.append("<div class=\"block\" style=\"").append(styleAttr).append("\">\n");
                    html.append("  <p>").append(escapeHtml(formatWeatherLine(block, weather))).append("</p>\n");
                    html.append("</div>\n");
                }
                break;
        }

        return html.toString();
    }

    /**
     * 블록 스타일을 인라인 CSS 문자열로 변환한다.
     */
    private String buildBlockStyleAttr(BlockStyle style, com.harupaper.server.device.PrinterProfile profile) {
        StringBuilder css = new StringBuilder();

        if (style != null) {
            String align = style.align();
            if (align != null) {
                css.append("text-align: ").append(escapeHtmlAttr(align)).append("; ");
            }

            Double fontSizePt = style.fontSizePt();
            if (fontSizePt != null) {
                css.append("font-size: ").append(convertPtToPx(fontSizePt, profile.dpi())).append("px; ");
            }

            Boolean bold = style.bold();
            if (bold != null && bold) {
                css.append("font-weight: bold; ");
            }

            Double marginTopMm = style.marginTopMm();
            if (marginTopMm != null && marginTopMm > 0) {
                css.append("margin-top: ").append((int) Math.round(marginTopMm * profile.dpi() / 25.4)).append("px; ");
            }

            Double marginBottomMm = style.marginBottomMm();
            if (marginBottomMm != null && marginBottomMm > 0) {
                css.append("margin-bottom: ").append((int) Math.round(marginBottomMm * profile.dpi() / 25.4)).append("px; ");
            }
        }

        return css.toString();
    }

    /**
     * 슬롯 폭 문자열("1/1", "1/2", "2/3" 등)을 백분율로 변환
     */
    private double parseSlotWidthPercentage(String width) {
        if (width == null) return 100.0;
        return switch (width) {
            case "1/1" -> 100.0;
            case "1/2" -> 50.0;
            case "1/3" -> 33.333333;
            case "2/3" -> 66.666667;
            default -> 100.0;
        };
    }

    /**
     * 길이 단위 변환: mm → px
     * mm / 25.4 × dpi
     */
    private double convertMmToPx(double mm, int dpi) {
        return mm / 25.4 * dpi;
    }

    /**
     * 글자 크기 변환: pt → px
     * pt / 72 × dpi
     */
    private double convertPtToPx(double pt, int dpi) {
        return pt / 72.0 * dpi;
    }

    /**
     * HTML 텍스트 이스케이프
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * HTML 속성값 이스케이프
     */
    private String escapeHtmlAttr(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 날짜 변수 빌드 ({{date}}, {{weekday}})
     */
    private Map<String, String> buildDateVariables(LocalDate targetDate) {
        Map<String, String> vars = new HashMap<>();

        // {{date}} = "2026년 9월 14일" — 대문자 Y/D는 각각 week-based-year/day-of-year라 소문자를 쓴다
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
        vars.put("date", targetDate.format(dateFormatter));

        // {{weekday}} = "월요일"
        String[] weekdays = {"일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일"};
        int dayOfWeek = targetDate.getDayOfWeek().getValue() % 7;
        vars.put("weekday", weekdays[dayOfWeek]);

        return vars;
    }

    /**
     * 날씨 조회 (실패해도 렌더 전체를 실패시키지 않는다)
     */
    private DailyWeather fetchWeather(LocalDate targetDate) {
        try {
            WeatherLocation location = weatherLocationProvider.getCurrent();
            return weatherProvider.getDaily(location.lat(), location.lon(), targetDate);
        } catch (Exception e) {
            log.warn("Failed to fetch weather for {}: {}", targetDate, e.getMessage());
            // 실패 표시: 날씨 블록에 표시할 텍스트
            return new DailyWeather(targetDate, null, null, null,
                    "날씨 정보를 가져오지 못했습니다", java.time.Instant.now(), "error");
        }
    }

    @SuppressWarnings("unchecked")
    private String formatWeatherLine(Block block, DailyWeather weather) {
        if ("error".equals(weather.source())
                || (weather.tempMin() == null && weather.tempMax() == null && weather.precipProb() == null
                && "날씨 정보를 가져오지 못했습니다".equals(weather.skyText()))) {
            return "날씨 정보를 가져오지 못했습니다";
        }

        java.util.List<String> fields = java.util.List.of("tempMin", "tempMax", "precipProb", "sky");
        if (block.props() != null && block.props().get("fields") instanceof java.util.List<?> listed && !listed.isEmpty()) {
            fields = (java.util.List<String>) listed;
        }

        StringBuilder line = new StringBuilder();
        WeatherLocation location = weatherLocationProvider.getCurrent();
        if (location.label() != null && !location.label().isBlank()) {
            line.append(location.label());
        }
        if (fields.contains("sky") && weather.skyText() != null) {
            line.append("  ").append(weather.skyText());
        }
        boolean tmin = fields.contains("tempMin") && weather.tempMin() != null;
        boolean tmax = fields.contains("tempMax") && weather.tempMax() != null;
        if (tmin || tmax) {
            line.append("  ");
            if (tmin) {
                line.append("최저 ").append(weather.tempMin()).append("°");
            }
            if (tmin && tmax) {
                line.append(" / ");
            }
            if (tmax) {
                line.append("최고 ").append(weather.tempMax()).append("°");
            }
        }
        if (fields.contains("precipProb") && weather.precipProb() != null) {
            line.append("  강수확률 ").append(weather.precipProb()).append("%");
        }
        if ("open-meteo-stale".equals(weather.source()) && weather.fetchedAt() != null) {
            ZonedDateTime fetched = weather.fetchedAt().atZone(ZoneId.of("Asia/Seoul"));
            line.append(" (").append(fetched.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).append(" 기준)");
        }
        return line.toString().trim();
    }

    /**
     * 텍스트에서 변수 치환 ({{date}}, {{weekday}})
     */
    private String substituteTextVariables(String text, Map<String, String> dateVariables) {
        if (text == null) return "";
        String result = text;
        result = result.replace("{{date}}", dateVariables.getOrDefault("date", "{{date}}"));
        result = result.replace("{{weekday}}", dateVariables.getOrDefault("weekday", "{{weekday}}"));
        return result;
    }

    /**
     * dateHeader 패턴 포맷팅 (format-schema.md 4.3절 토큰만 치환, 나머지 문자는 그대로).
     * 토큰: YYYY(2026), MM(09), M(9), DD(05), D(5), dddd(월요일), ddd(월).
     * 긴 토큰(dddd, YYYY, MM, DD)부터 치환해야 짧은 토큰(ddd, M, D)이 먼저 먹어버리지 않는다.
     */
    private String formatDateHeader(String pattern, LocalDate targetDate) {
        String[] weekdaysFull = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
        String[] weekdaysShort = {"월", "화", "수", "목", "금", "토", "일"};
        int dow = targetDate.getDayOfWeek().getValue() - 1; // MONDAY=1 -> index 0

        String result = pattern;
        result = result.replace("dddd", weekdaysFull[dow]);
        result = result.replace("YYYY", String.format("%04d", targetDate.getYear()));
        result = result.replace("MM", String.format("%02d", targetDate.getMonthValue()));
        result = result.replace("DD", String.format("%02d", targetDate.getDayOfMonth()));
        result = result.replace("ddd", weekdaysShort[dow]);
        result = result.replace("M", String.valueOf(targetDate.getMonthValue()));
        result = result.replace("D", String.valueOf(targetDate.getDayOfMonth()));
        return result;
    }

    /**
     * 이미지 에셋을 data: URI로 변환
     */
    private String getImageDataUri(String assetId) {
        try {
            Optional<Asset> asset = assetRepository.findById(assetId);
            if (asset.isEmpty()) {
                log.warn("Asset not found: {}", assetId);
                return "";
            }

            Asset a = asset.get();
            // haru-files/{path} 읽기
            byte[] imageData = Files.readAllBytes(Paths.get(filesDir).resolve(a.getPath()));
            String base64 = Base64.getEncoder().encodeToString(imageData);
            return "data:" + a.getContentType() + ";base64," + base64;
        } catch (IOException e) {
            log.error("Failed to load asset: {}", assetId, e);
            return "";
        }
    }
}
