package com.harupaper.server.format;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FormatDocument에 대한 순수 계산 헬퍼. 검증 로직은 포함하지 않는다(FormatValidator가 담당).
 *
 * 동적 포맷 여부(위젯 중 dynamic=true가 하나라도 있는지)는 더 이상 여기서 판단하지 않는다 —
 * 위젯 종류·dynamic 플래그는 WidgetRegistry(스프링 빈)만 알고 있으므로
 * WidgetRegistry.hasDynamic(document.widgets())를 직접 쓴다(FormatService, RenderScheduler).
 */
@Slf4j
public final class FormatDocumentSupport {

    private FormatDocumentSupport() {
    }

    /**
     * 저장된 포맷 body(JSON 문자열)를 읽는다. schemaVersion 1·2는 자동으로 v3로 up-convert하고,
     * 3이면 그대로 파싱한다.
     *
     * 이 메서드는 읽기 경로(조회, 렌더, 미리보기 등)에서만 사용된다. 쓰기는 항상 v3로 검증한다.
     *
     * @param bodyJson FormatDocument의 JSON 문자열 (format.getBody())
     * @param mapper   Jackson ObjectMapper
     * @return FormatDocument (v3), up-convert되었을 수도 있음
     */
    public static FormatDocument readDocument(String bodyJson, ObjectMapper mapper) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(bodyJson, Map.class);
            Map<String, Object> v3Raw = convertToV3Raw(raw);

            ObjectMapper lenient = mapper.copy();
            lenient.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return lenient.convertValue(v3Raw, FormatDocument.class);
        } catch (Exception e) {
            log.error("Failed to read format document", e);
            throw new RuntimeException("Failed to read format document", e);
        }
    }

    /**
     * 가져오기(import) 원본 JSON(Map)이 schemaVersion 1·2이면 v3로 변환해서 돌려주고,
     * 아니면(이미 3) 그대로 돌려준다. "assets" 키(가져오기 전용, FormatDocument에는 없는 필드)는
     * 보존한다. import는 사용자가 올린 파일을 검증기(schemaVersion==3만 허용)에 넘기기 직전에
     * 거쳐야 한다 — 안 그러면 위젯 그리드 도입 이전에 내보낸 v1·v2 파일을 아무도 다시 가져올 수 없다.
     *
     * mapper 인자는 up-convert 자체(순수 Map 변환)에는 쓰이지 않지만, readDocument와 시그니처를
     * 맞춰 두면 호출부가 항상 같은 방식으로 부를 수 있다(.temp/07 3.3절 명시).
     */
    public static Map<String, Object> upConvertRawImportIfNeeded(Map<String, Object> raw, ObjectMapper mapper) {
        Object versionObj = raw.get("schemaVersion");
        int version = (versionObj instanceof Number n) ? n.intValue() : 3;
        if (version != 1 && version != 2) {
            return raw;
        }
        Map<String, Object> converted = new LinkedHashMap<>(convertToV3Raw(raw));
        if (raw.containsKey("assets")) {
            converted.put("assets", raw.get("assets"));
        }
        return converted;
    }

    /**
     * raw JSON(v1·v2·v3 어느 것이든)을 v3 raw Map으로 변환한다. 순수 함수 — WidgetRegistry 등
     * 스프링 빈에 의존하지 않는다(up-convert는 위젯 type 문자열만 만들 뿐, 그 type이 실제로
     * 등록돼 있는지는 검증하지 않는다 — 검증은 FormatValidator의 몫이다).
     */
    private static Map<String, Object> convertToV3Raw(Map<String, Object> raw) {
        Object versionObj = raw.get("schemaVersion");
        int version = (versionObj instanceof Number n) ? n.intValue() : 3;
        if (version == 3) {
            return raw;
        }
        if (version != 1 && version != 2) {
            log.error("Unsupported schemaVersion: {}", version);
            throw new RuntimeException("Unsupported schemaVersion: " + version);
        }

        List<Map<String, Object>> widgets = new ArrayList<>();
        if (version == 1) {
            Object blocksObj = raw.get("blocks");
            if (blocksObj instanceof List<?> blocks) {
                for (Object blockObj : blocks) {
                    Map<String, Object> block = asMapOrNull(blockObj);
                    if (block == null) {
                        continue;
                    }
                    Map<String, Object> widget = convertBlockToWidget(block, null);
                    if (widget != null) {
                        widgets.add(widget);
                    }
                }
            }
        } else {
            Object rowsObj = raw.get("rows");
            if (rowsObj instanceof List<?> rows) {
                for (Object rowObj : rows) {
                    Map<String, Object> row = asMapOrNull(rowObj);
                    if (row == null) {
                        continue;
                    }
                    Object slotsObj = row.get("slots");
                    if (slotsObj instanceof List<?> slots) {
                        for (Object slotObj : slots) {
                            Map<String, Object> slot = asMapOrNull(slotObj);
                            if (slot == null) {
                                continue;
                            }
                            Map<String, Object> block = asMapOrNull(slot.get("block"));
                            if (block == null) {
                                continue;
                            }
                            // v2면 slot id를 그대로 재사용한다(.temp/07 3.3절) — 새로 uuid를
                            // 만들 필요 없이 이미 문서 안에서 유일한 id가 있었으므로.
                            Object slotIdObj = slot.get("id");
                            String reuseId = (slotIdObj instanceof String s && !s.isBlank()) ? s : null;
                            Map<String, Object> widget = convertBlockToWidget(block, reuseId);
                            if (widget != null) {
                                widgets.add(widget);
                            }
                        }
                    }
                }
            }
        }

        // 빈 결과(블록 0개)는 위젯 "1개 이상" 규칙을 지키기 위해 빈 text 위젯 하나를 넣는다.
        if (widgets.isEmpty()) {
            widgets.add(emptyTextWidget());
        }

        Map<String, Object> v3 = new LinkedHashMap<>();
        v3.put("schemaVersion", 3);
        v3.put("meta", raw.get("meta"));
        if (raw.get("style") != null) {
            v3.put("style", raw.get("style"));
        }
        v3.put("widgets", widgets);
        return v3;
    }

    /**
     * v1·v2 블록 1개를 v3 위젯 raw Map으로 변환한다(.temp/07 3.3절 변환표).
     *
     * @param reuseId v2에서 슬롯 id를 재사용할 때만 넘긴다. null이면 새 uuid를 만든다(v1)
     * @return 변환된 위젯, 알 수 없는 블록 type이면 null(버린다)
     */
    private static Map<String, Object> convertBlockToWidget(Map<String, Object> block, String reuseId) {
        Object typeObj = block.get("type");
        if (!(typeObj instanceof String type)) {
            return null;
        }
        Map<String, Object> props = asMap(block.get("props"));
        Map<String, Object> style = asMap(block.get("style"));

        Map<String, Object> newProps = new LinkedHashMap<>();
        String size;
        switch (type) {
            case "text" -> {
                size = "4xauto";
                newProps.put("text", props.getOrDefault("text", ""));
                copyStyleProps(style, newProps);
            }
            case "dateHeader" -> {
                size = "4x1";
                if (props.get("pattern") != null) {
                    newProps.put("pattern", props.get("pattern"));
                }
                copyStyleProps(style, newProps);
            }
            case "image" -> {
                size = "4xauto";
                if (props.get("assetId") != null) {
                    newProps.put("assetId", props.get("assetId"));
                }
                if (props.get("widthPercent") != null) {
                    newProps.put("widthPercent", props.get("widthPercent"));
                }
                Object align = style.get("align");
                if (align != null) {
                    newProps.put("align", align);
                }
            }
            case "weather" -> {
                // 옛 블록에는 위치가 없었다 — 렌더는 항상 서울시청 좌표로 이뤄지고 있었다
                // (WeatherLocationProvider.getCurrent()가 사용자 설정을 무시하는 버그, [확인됨·코드]).
                // up-convert도 같은 값을 넣어 결과가 바뀌지 않게 한다. fields는 버린다.
                size = "4x2";
                Map<String, Object> location = new LinkedHashMap<>();
                location.put("label", "서울");
                location.put("lat", 37.5665);
                location.put("lon", 126.9780);
                newProps.put("location", location);
            }
            default -> {
                log.warn("Unknown legacy block type during up-convert, dropped: {}", type);
                return null;
            }
        }

        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("id", reuseId != null ? reuseId : UUID.randomUUID().toString());
        widget.put("type", type);
        widget.put("size", size);
        widget.put("props", newProps);
        return widget;
    }

    /** 블록 style(align/fontSizePt/bold)을 위젯 props로 옮긴다. marginTopMm/marginBottomMm은 버린다. */
    private static void copyStyleProps(Map<String, Object> style, Map<String, Object> target) {
        Object align = style.get("align");
        if (align != null) {
            target.put("align", align);
        }
        Object fontSizePt = style.get("fontSizePt");
        if (fontSizePt instanceof Number num) {
            // 위젯 props의 fontSizePt는 integer 필드다(PropField.integer) — 반올림해서 넣는다.
            target.put("fontSizePt", (int) Math.round(num.doubleValue()));
        }
        Object bold = style.get("bold");
        if (bold != null) {
            target.put("bold", bold);
        }
    }

    private static Map<String, Object> emptyTextWidget() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", "");
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("id", UUID.randomUUID().toString());
        widget.put("type", "text");
        widget.put("size", "4xauto");
        widget.put("props", props);
        return widget;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (value instanceof Map) ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapOrNull(Object value) {
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }
}
