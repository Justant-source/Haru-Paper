package com.harupaper.server.format;

import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.widget.GridSpec;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPropsValidator;
import com.harupaper.server.widget.WidgetRegistry;
import com.harupaper.server.widget.WidgetSize;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * FormatDocument v3(위젯 그리드)를 검증한다(.temp/07-위젯그리드-작업지시서.md 3절).
 * 위반을 전부 모아 하나의 ValidationException으로 던진다.
 */
@Component
public class FormatValidator {

    private static final int MAX_FORMAT_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_AUTHOR_LENGTH = 100;
    private static final double MIN_BASE_FONT_SIZE = 6.0;
    private static final double MAX_BASE_FONT_SIZE = 48.0;
    private static final double MIN_LINE_HEIGHT = 1.0;
    private static final double MAX_LINE_HEIGHT = 3.0;
    private static final double MIN_MARGIN = 0.0;
    private static final double MAX_MARGIN = 20.0;
    private static final double MAX_BLOCK_GAP = 30.0;
    // 위젯 id 길이 상한(.temp/07 3.2절 "id 문자열 1~64자")
    private static final int MAX_WIDGET_ID_LENGTH = 64;

    private static final Set<String> VALID_FONT_FAMILIES = Set.of("Pretendard", "Noto Sans KR");
    private static final Set<String> VALID_DIVIDERS = Set.of("none", "line", "dashed");

    private static final Set<String> VALID_ROOT_KEYS = Set.of("schemaVersion", "meta", "style", "widgets");
    private static final Set<String> VALID_META_KEYS = Set.of("name", "author", "description", "forkedFrom");
    private static final Set<String> VALID_STYLE_KEYS = Set.of(
            "fontFamily", "baseFontSizePt", "lineHeight", "marginMm", "blockGapMm", "divider");
    private static final Set<String> VALID_MARGIN_KEYS = Set.of("top", "right", "bottom", "left");
    // widgets[i]의 최상위 키. props 안쪽 키는 위젯 type마다 다르므로 WidgetPropsValidator가 본다.
    private static final Set<String> VALID_WIDGET_KEYS = Set.of("id", "type", "size", "props");

    private final WidgetRegistry widgetRegistry;
    private final WidgetPropsValidator widgetPropsValidator;

    public FormatValidator(WidgetRegistry widgetRegistry, WidgetPropsValidator widgetPropsValidator) {
        this.widgetRegistry = widgetRegistry;
        this.widgetPropsValidator = widgetPropsValidator;
    }

    /**
     * 원본 JSON(Map)에서 화이트리스트에 없는 키를 찾는다. Jackson으로 FormatDocument/WidgetInstance
     * 레코드에 역직렬화하고 나면 알 수 없는 키는 이미 사라진 뒤라 이 시점에만 검출할 수 있다.
     *
     * @param allowAssets 가져오기(import)에서만 true — 최상위 assets 키를 허용한다
     */
    public void validateRawKeys(Map<String, Object> raw, boolean allowAssets, List<ValidationException.FieldError> errors) {
        if (raw == null) {
            return;
        }
        Set<String> allowedRoot = allowAssets
                ? Stream.concat(VALID_ROOT_KEYS.stream(), Stream.of("assets")).collect(Collectors.toSet())
                : VALID_ROOT_KEYS;
        rejectUnknownKeys(raw, allowedRoot, "", errors);

        asMap(raw.get("meta")).ifPresent(meta -> rejectUnknownKeys(meta, VALID_META_KEYS, "meta.", errors));

        asMap(raw.get("style")).ifPresent(style -> {
            rejectUnknownKeys(style, VALID_STYLE_KEYS, "style.", errors);
            asMap(style.get("marginMm")).ifPresent(margin ->
                    rejectUnknownKeys(margin, VALID_MARGIN_KEYS, "style.marginMm.", errors));
        });

        Object widgetsObj = raw.get("widgets");
        if (widgetsObj instanceof List<?> widgets) {
            for (int i = 0; i < widgets.size(); i++) {
                final String widgetPath = "widgets[" + i + "].";
                asMap(widgets.get(i)).ifPresent(widget ->
                        rejectUnknownKeys(widget, VALID_WIDGET_KEYS, widgetPath, errors));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> asMap(Object value) {
        return value instanceof Map ? Optional.of((Map<String, Object>) value) : Optional.empty();
    }

    private void rejectUnknownKeys(Map<String, Object> map, Set<String> allowedKeys, String pathPrefix,
                                    List<ValidationException.FieldError> errors) {
        for (String key : map.keySet()) {
            if (!allowedKeys.contains(key)) {
                errors.add(new ValidationException.FieldError(pathPrefix + key,
                        "unknown field: " + pathPrefix + key));
            }
        }
    }

    /**
     * 원본 JSON부터 구조(알 수 없는 키) 검증 + FormatDocument 변환 + 값 검증까지 한 번에 하고,
     * 위반을 전부 모아 하나의 ValidationException으로 던진다. create/update/편집본 미리보기가 쓴다.
     *
     * @param allowAssets   가져오기(import)에서만 true
     * @param requestUserId asset 필드 소유권 검사용 현재 사용자 id. import의 리매핑 전 1차 검증처럼
     *                      의도적으로 건너뛸 때만 null(FormatController 참고, WidgetPropsValidator 문서화).
     */
    public FormatDocument validateAndParse(Map<String, Object> raw,
                                            boolean allowAssets,
                                            com.fasterxml.jackson.databind.ObjectMapper mapper,
                                            String requestUserId) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        validateRawKeys(raw, allowAssets, errors);

        Map<String, Object> forConversion = raw;
        if (allowAssets && raw != null && raw.containsKey("assets")) {
            forConversion = new java.util.HashMap<>(raw);
            forConversion.remove("assets");
        }

        FormatDocument document = null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper lenient = mapper.copy();
            lenient.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            document = lenient.convertValue(forConversion, FormatDocument.class);
        } catch (Exception e) {
            errors.add(new ValidationException.FieldError("", "malformed format document: " + e.getMessage()));
        }

        if (document != null) {
            collectDocumentErrors(document, errors, requestUserId);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("format document is invalid", errors);
        }
        return document;
    }

    public void validate(FormatDocument document, String requestUserId) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        if (document == null) {
            errors.add(new ValidationException.FieldError("", "format document is required"));
            throw new ValidationException("format document is invalid", errors);
        }
        collectDocumentErrors(document, errors, requestUserId);
        if (!errors.isEmpty()) {
            throw new ValidationException("format document is invalid", errors);
        }
    }

    private void collectDocumentErrors(FormatDocument document, List<ValidationException.FieldError> errors,
                                        String requestUserId) {
        if (document.schemaVersion() != 3) {
            errors.add(new ValidationException.FieldError("schemaVersion",
                "unsupported schemaVersion: " + document.schemaVersion() + " (only 3 supported)"));
        }

        if (document.meta() == null) {
            errors.add(new ValidationException.FieldError("meta", "meta is required"));
        } else {
            validateMeta(document.meta(), errors);
        }

        if (document.style() != null) {
            validateStyle(document.style(), errors);
        }

        if (document.widgets() == null) {
            errors.add(new ValidationException.FieldError("widgets", "widgets is required"));
        } else if (document.widgets().isEmpty() || document.widgets().size() > GridSpec.MAX_WIDGETS) {
            errors.add(new ValidationException.FieldError("widgets",
                "widgets must have 1 to " + GridSpec.MAX_WIDGETS + " items, got " + document.widgets().size()));
        } else {
            Set<String> seenIds = new HashSet<>();
            for (int i = 0; i < document.widgets().size(); i++) {
                validateWidget(document.widgets().get(i), i, seenIds, errors, requestUserId);
            }
        }
    }

    private void validateMeta(FormatMeta meta, List<ValidationException.FieldError> errors) {
        if (meta.name() == null || meta.name().isEmpty() || meta.name().length() > MAX_FORMAT_NAME_LENGTH) {
            errors.add(new ValidationException.FieldError("meta.name",
                "name must be 1 to " + MAX_FORMAT_NAME_LENGTH + " characters, got '" + meta.name() + "'"));
        }

        if (meta.author() != null && meta.author().length() > MAX_AUTHOR_LENGTH) {
            errors.add(new ValidationException.FieldError("meta.author",
                "author must be 0 to " + MAX_AUTHOR_LENGTH + " characters"));
        }

        if (meta.description() != null && meta.description().length() > MAX_DESCRIPTION_LENGTH) {
            errors.add(new ValidationException.FieldError("meta.description",
                "description must be 0 to " + MAX_DESCRIPTION_LENGTH + " characters"));
        }

        // forkedFrom should be server-generated, ignored if provided by client
    }

    private void validateStyle(FormatStyle style, List<ValidationException.FieldError> errors) {
        if (style.fontFamily() != null && !VALID_FONT_FAMILIES.contains(style.fontFamily())) {
            errors.add(new ValidationException.FieldError("style.fontFamily",
                "fontFamily must be one of " + VALID_FONT_FAMILIES + ", got '" + style.fontFamily() + "'"));
        }

        if (style.baseFontSizePt() != null && (style.baseFontSizePt() < MIN_BASE_FONT_SIZE || style.baseFontSizePt() > MAX_BASE_FONT_SIZE)) {
            errors.add(new ValidationException.FieldError("style.baseFontSizePt",
                "baseFontSizePt must be " + MIN_BASE_FONT_SIZE + " to " + MAX_BASE_FONT_SIZE));
        }

        if (style.lineHeight() != null && (style.lineHeight() < MIN_LINE_HEIGHT || style.lineHeight() > MAX_LINE_HEIGHT)) {
            errors.add(new ValidationException.FieldError("style.lineHeight",
                "lineHeight must be " + MIN_LINE_HEIGHT + " to " + MAX_LINE_HEIGHT));
        }

        if (style.marginMm() != null) {
            validateMargin(style.marginMm(), "style.marginMm", errors);
        }

        // v3 그리드는 간격이 GridSpec.GAP_MM 고정이라 blockGapMm은 받되 무시하지만, 범위 자체는
        // 계속 검증해 둔다(호환 필드라고 아무 값이나 통과시키지 않는다 — .temp/07 3.1절).
        if (style.blockGapMm() != null && (style.blockGapMm() < MIN_MARGIN || style.blockGapMm() > MAX_BLOCK_GAP)) {
            errors.add(new ValidationException.FieldError("style.blockGapMm",
                "blockGapMm must be 0 to " + MAX_BLOCK_GAP));
        }

        if (style.divider() != null && !VALID_DIVIDERS.contains(style.divider())) {
            errors.add(new ValidationException.FieldError("style.divider",
                "divider must be one of " + VALID_DIVIDERS + ", got '" + style.divider() + "'"));
        }
    }

    private void validateMargin(MarginMm margin, String path, List<ValidationException.FieldError> errors) {
        if (margin.top() != null && (margin.top() < MIN_MARGIN || margin.top() > MAX_MARGIN)) {
            errors.add(new ValidationException.FieldError(path + ".top", "margin must be 0 to " + MAX_MARGIN));
        }
        if (margin.right() != null && (margin.right() < MIN_MARGIN || margin.right() > MAX_MARGIN)) {
            errors.add(new ValidationException.FieldError(path + ".right", "margin must be 0 to " + MAX_MARGIN));
        }
        if (margin.bottom() != null && (margin.bottom() < MIN_MARGIN || margin.bottom() > MAX_MARGIN)) {
            errors.add(new ValidationException.FieldError(path + ".bottom", "margin must be 0 to " + MAX_MARGIN));
        }
        if (margin.left() != null && (margin.left() < MIN_MARGIN || margin.left() > MAX_MARGIN)) {
            errors.add(new ValidationException.FieldError(path + ".left", "margin must be 0 to " + MAX_MARGIN));
        }
    }

    private void validateWidget(WidgetInstance widget, int index, Set<String> seenIds,
                                 List<ValidationException.FieldError> errors, String requestUserId) {
        String path = "widgets[" + index + "]";

        if (widget.id() == null || widget.id().isEmpty() || widget.id().length() > MAX_WIDGET_ID_LENGTH) {
            errors.add(new ValidationException.FieldError(path + ".id",
                "id must be 1 to " + MAX_WIDGET_ID_LENGTH + " characters"));
        } else if (!seenIds.add(widget.id())) {
            errors.add(new ValidationException.FieldError(path + ".id", "duplicate widget id: " + widget.id()));
        }

        if (widget.type() == null) {
            errors.add(new ValidationException.FieldError(path + ".type", "type is required"));
            return;
        }
        Optional<Widget> found = widgetRegistry.find(widget.type());
        if (found.isEmpty()) {
            errors.add(new ValidationException.FieldError(path + ".type", "unknown widget type: " + widget.type()));
            return;
        }
        Widget impl = found.get();
        WidgetDescriptor descriptor = impl.descriptor();

        if (widget.size() == null || descriptor.size(widget.size()) == null) {
            errors.add(new ValidationException.FieldError(path + ".size",
                "size must be one of " + descriptor.sizes().stream().map(WidgetSize::id).toList()
                    + ", got '" + widget.size() + "'"));
        }

        widgetPropsValidator.validate(impl, widget.props(), path + ".props", errors, requestUserId);
    }
}
