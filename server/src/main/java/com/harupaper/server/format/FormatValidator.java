package com.harupaper.server.format;

import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.common.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates FormatDocument against format-schema.md rules.
 * Collects all violations before throwing ValidationException.
 */
@Component
public class FormatValidator {

    private static final int MAX_FORMAT_NAME_LENGTH = 100;
    private static final int MIN_BLOCKS = 1;
    private static final int MAX_BLOCKS = 50;
    private static final int MAX_TEXT_LENGTH = 5000;
    private static final int MAX_PATTERN_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_AUTHOR_LENGTH = 100;
    private static final int MAX_IMAGE_WIDTH_PERCENT = 100;
    private static final int MIN_IMAGE_WIDTH_PERCENT = 10;
    private static final double MIN_FONT_SIZE = 6.0;
    private static final double MAX_FONT_SIZE = 72.0;
    private static final double MIN_BASE_FONT_SIZE = 6.0;
    private static final double MAX_BASE_FONT_SIZE = 48.0;
    private static final double MIN_LINE_HEIGHT = 1.0;
    private static final double MAX_LINE_HEIGHT = 3.0;
    private static final double MIN_MARGIN = 0.0;
    private static final double MAX_MARGIN = 20.0;
    private static final double MAX_BLOCK_MARGIN = 30.0;
    private static final double MAX_BLOCK_GAP = 30.0;

    private static final Set<String> VALID_BLOCK_TYPES = Set.of("text", "image", "dateHeader", "weather");
    private static final Set<String> VALID_BLOCK_STYLE_KEYS = Set.of("align", "fontSizePt", "bold", "marginTopMm", "marginBottomMm");
    private static final Set<String> VALID_ALIGN_VALUES = Set.of("left", "center", "right");
    private static final Set<String> VALID_FONT_FAMILIES = Set.of("Pretendard", "Noto Sans KR");
    private static final Set<String> VALID_DIVIDERS = Set.of("none", "line", "dashed");
    private static final Set<String> VALID_WEATHER_FIELDS = Set.of("tempMin", "tempMax", "precipProb", "sky");

    private final AssetRepository assetRepository;

    public FormatValidator(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    private static final Set<String> VALID_ROOT_KEYS = Set.of("schemaVersion", "meta", "style", "rows");
    private static final Set<String> VALID_META_KEYS = Set.of("name", "author", "description", "forkedFrom");
    private static final Set<String> VALID_STYLE_KEYS = Set.of(
            "fontFamily", "baseFontSizePt", "lineHeight", "marginMm", "blockGapMm", "divider");
    private static final Set<String> VALID_MARGIN_KEYS = Set.of("top", "right", "bottom", "left");
    private static final Set<String> VALID_BLOCK_KEYS = Set.of("type", "props", "style");
    private static final Map<String, Set<String>> VALID_PROPS_KEYS_BY_TYPE = Map.of(
            "text", Set.of("text"),
            "image", Set.of("assetId", "widthPercent"),
            "dateHeader", Set.of("pattern"),
            "weather", Set.of("location", "fields")
    );

    /**
     * 원본 JSON(Map)에서 화이트리스트에 없는 키를 찾는다. Jackson으로 FormatDocument/Block/BlockStyle
     * 레코드에 역직렬화하고 나면 알 수 없는 키는 이미 사라진 뒤라 이 시점에만 검출할 수 있다
     * (format-schema.md 3~4절 "이 표에 없는 키는 거부(422)").
     *
     * @param allowAssets 가져오기(import)에서만 true — 최상위 assets 키를 허용한다
     */
    public void validateRawKeys(Map<String, Object> raw, boolean allowAssets, List<ValidationException.FieldError> errors) {
        if (raw == null) {
            return;
        }
        Set<String> allowedRoot = allowAssets
                ? java.util.stream.Stream.concat(VALID_ROOT_KEYS.stream(), java.util.stream.Stream.of("assets"))
                        .collect(java.util.stream.Collectors.toSet())
                : VALID_ROOT_KEYS;
        rejectUnknownKeys(raw, allowedRoot, "", errors);

        asMap(raw.get("meta")).ifPresent(meta -> rejectUnknownKeys(meta, VALID_META_KEYS, "meta.", errors));

        asMap(raw.get("style")).ifPresent(style -> {
            rejectUnknownKeys(style, VALID_STYLE_KEYS, "style.", errors);
            asMap(style.get("marginMm")).ifPresent(margin ->
                    rejectUnknownKeys(margin, VALID_MARGIN_KEYS, "style.marginMm.", errors));
        });

        Object rowsObj = raw.get("rows");
        if (rowsObj instanceof List<?> rows) {
            for (int i = 0; i < rows.size(); i++) {
                final String rowPath = "rows[" + i + "].";
                asMap(rows.get(i)).ifPresent(row -> {
                    Set<String> validRowKeys = Set.of("id", "slots");
                    rejectUnknownKeys(row, validRowKeys, rowPath, errors);

                    Object slotsObj = row.get("slots");
                    if (slotsObj instanceof List<?> slots) {
                        for (int j = 0; j < slots.size(); j++) {
                            final String slotPath = rowPath + "slots[" + j + "].";
                            asMap(slots.get(j)).ifPresent(slot -> {
                                Set<String> validSlotKeys = Set.of("id", "width", "block");
                                rejectUnknownKeys(slot, validSlotKeys, slotPath, errors);

                                asMap(slot.get("block")).ifPresent(block -> {
                                    rejectUnknownKeys(block, VALID_BLOCK_KEYS, slotPath + "block.", errors);
                                    asMap(block.get("style")).ifPresent(style ->
                                            rejectUnknownKeys(style, VALID_BLOCK_STYLE_KEYS, slotPath + "block.style.", errors));
                                    Object typeObj = block.get("type");
                                    Set<String> validPropsKeys = (typeObj instanceof String type) ? VALID_PROPS_KEYS_BY_TYPE.get(type) : null;
                                    if (validPropsKeys != null) {
                                        asMap(block.get("props")).ifPresent(props ->
                                                rejectUnknownKeys(props, validPropsKeys, slotPath + "block.props.", errors));
                                    }
                                });
                            });
                        }
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<Map<String, Object>> asMap(Object value) {
        return value instanceof Map ? java.util.Optional.of((Map<String, Object>) value) : java.util.Optional.empty();
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
     * @param allowAssets 가져오기(import)에서만 true
     */
    public FormatDocument validateAndParse(Map<String, Object> raw,
                                            boolean allowAssets,
                                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
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
            collectDocumentErrors(document, errors);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("format document is invalid", errors);
        }
        return document;
    }

    public void validate(FormatDocument document) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        if (document == null) {
            errors.add(new ValidationException.FieldError("", "format document is required"));
            throw new ValidationException("format document is invalid", errors);
        }
        collectDocumentErrors(document, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException("format document is invalid", errors);
        }
    }

    private void collectDocumentErrors(FormatDocument document, List<ValidationException.FieldError> errors) {
        // schemaVersion
        if (document.schemaVersion() != 2) {
            errors.add(new ValidationException.FieldError("schemaVersion",
                "unsupported schemaVersion: " + document.schemaVersion() + " (only 2 supported)"));
        }

        // meta
        if (document.meta() == null) {
            errors.add(new ValidationException.FieldError("meta", "meta is required"));
        } else {
            validateMeta(document.meta(), errors);
        }

        // style (optional, filled with defaults if missing)
        if (document.style() != null) {
            validateStyle(document.style(), errors);
        }

        // rows
        if (document.rows() == null) {
            errors.add(new ValidationException.FieldError("rows", "rows is required"));
        } else if (document.rows().isEmpty() || document.rows().size() > 30) {
            errors.add(new ValidationException.FieldError("rows",
                "rows must have 1 to 30 items, got " + document.rows().size()));
        } else {
            for (int i = 0; i < document.rows().size(); i++) {
                validateRow(document.rows().get(i), i, errors);
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

    private void validateRow(com.harupaper.server.format.Row row, int index, List<ValidationException.FieldError> errors) {
        String rowPath = "rows[" + index + "]";

        if (row.slots() == null || row.slots().isEmpty() || row.slots().size() > 2) {
            errors.add(new ValidationException.FieldError(rowPath + ".slots",
                "slots must have 1 to 2 items, got " + (row.slots() != null ? row.slots().size() : 0)));
            return;
        }

        // 슬롯 폭 조합 검증
        if (row.slots().size() == 1) {
            // 1슬롯 행: "1/1"만 허용
            com.harupaper.server.format.Slot slot = row.slots().get(0);
            if (!"1/1".equals(slot.width())) {
                errors.add(new ValidationException.FieldError(rowPath + ".slots[0].width",
                    "single-slot row must have width \"1/1\", got \"" + slot.width() + "\""));
            }
            validateSlot(slot, rowPath, 0, errors);
        } else {
            // 2슬롯 행: ("1/2","1/2") | ("2/3","1/3") | ("1/3","2/3")만 허용
            String width0 = row.slots().get(0).width();
            String width1 = row.slots().get(1).width();
            boolean validPair = false;
            if (("1/2".equals(width0) && "1/2".equals(width1)) ||
                ("2/3".equals(width0) && "1/3".equals(width1)) ||
                ("1/3".equals(width0) && "2/3".equals(width1))) {
                validPair = true;
            }

            if (!validPair) {
                errors.add(new ValidationException.FieldError(rowPath + ".slots",
                    "invalid width pair: (\"" + width0 + "\",\"" + width1 + "\")"));
            }

            for (int i = 0; i < 2; i++) {
                validateSlot(row.slots().get(i), rowPath, i, errors);
            }
        }
    }

    private void validateSlot(com.harupaper.server.format.Slot slot, String rowPath, int slotIndex, List<ValidationException.FieldError> errors) {
        String slotPath = rowPath + ".slots[" + slotIndex + "]";

        if (slot.block() == null) {
            errors.add(new ValidationException.FieldError(slotPath + ".block", "block is required"));
            return;
        }

        validateBlock(slot.block(), slotPath + ".block", errors);
    }

    private void validateBlock(Block block, String blockPath, List<ValidationException.FieldError> errors) {
        if (block.type() == null || !VALID_BLOCK_TYPES.contains(block.type())) {
            errors.add(new ValidationException.FieldError(blockPath + ".type",
                "block type must be one of " + VALID_BLOCK_TYPES + ", got '" + block.type() + "'"));
            return;
        }

        if (block.props() == null) {
            errors.add(new ValidationException.FieldError(blockPath + ".props", "props is required"));
            return;
        }

        validateBlockPropsWithPath(block, blockPath, errors);
        validateBlockStyle(block.style(), blockPath, errors);
    }

    private void validateBlock(Block block, int index, List<ValidationException.FieldError> errors) {
        String blockPath = "blocks[" + index + "]";
        validateBlock(block, blockPath, errors);
    }

    private void validateBlockPropsWithPath(Block block, String blockPath, List<ValidationException.FieldError> errors) {
        switch (block.type()) {
            case "text":
                validateTextProps(block.props(), blockPath, errors);
                break;
            case "image":
                validateImageProps(block.props(), blockPath, errors);
                break;
            case "dateHeader":
                validateDateHeaderProps(block.props(), blockPath, errors);
                break;
            case "weather":
                validateWeatherProps(block.props(), blockPath, errors);
                break;
        }
    }

    private void validateBlockProps(Block block, int index, List<ValidationException.FieldError> errors) {
        String blockPath = "blocks[" + index + "]";
        validateBlockPropsWithPath(block, blockPath, errors);
    }

    private void validateTextProps(Map<String, Object> props, String blockPath, List<ValidationException.FieldError> errors) {
        Object textObj = props.get("text");
        if (textObj == null) {
            errors.add(new ValidationException.FieldError(blockPath + ".props.text", "text is required"));
            return;
        }
        if (!(textObj instanceof String text)) {
            errors.add(new ValidationException.FieldError(blockPath + ".props.text", "text must be a string"));
            return;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            errors.add(new ValidationException.FieldError(blockPath + ".props.text",
                "text must be 0 to " + MAX_TEXT_LENGTH + " characters"));
        }
    }

    private void validateImageProps(Map<String, Object> props, String blockPath, List<ValidationException.FieldError> errors) {
        Object assetIdObj = props.get("assetId");
        if (assetIdObj == null) {
            errors.add(new ValidationException.FieldError(blockPath + ".props.assetId", "assetId is required"));
            return;
        }
        if (!(assetIdObj instanceof String assetId)) {
            errors.add(new ValidationException.FieldError(blockPath + ".props.assetId", "assetId must be a string"));
            return;
        }

        if (!assetRepository.existsById(assetId)) {
            errors.add(new ValidationException.FieldError(blockPath + ".props.assetId",
                "asset not found: " + assetId));
        }

        // widthPercent is optional, default 100
        Object widthPercentObj = props.get("widthPercent");
        if (widthPercentObj != null) {
            if (!(widthPercentObj instanceof Number widthPercent)) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.widthPercent",
                    "widthPercent must be a number"));
                return;
            }
            int wp = widthPercent.intValue();
            if (wp < MIN_IMAGE_WIDTH_PERCENT || wp > MAX_IMAGE_WIDTH_PERCENT) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.widthPercent",
                    "widthPercent must be " + MIN_IMAGE_WIDTH_PERCENT + " to " + MAX_IMAGE_WIDTH_PERCENT));
            }
        }
    }

    private void validateDateHeaderProps(Map<String, Object> props, String blockPath, List<ValidationException.FieldError> errors) {
        // pattern is optional, default is provided
        Object patternObj = props.get("pattern");
        if (patternObj != null) {
            if (!(patternObj instanceof String pattern)) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.pattern", "pattern must be a string"));
                return;
            }
            if (pattern.length() > MAX_PATTERN_LENGTH) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.pattern",
                    "pattern must be 0 to " + MAX_PATTERN_LENGTH + " characters"));
            }
        }
    }

    private void validateWeatherProps(Map<String, Object> props, String blockPath, List<ValidationException.FieldError> errors) {
        // location is optional, default "default"
        Object locationObj = props.get("location");
        if (locationObj != null) {
            if (!(locationObj instanceof String location)) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.location", "location must be a string"));
            } else if (!"default".equals(location)) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.location",
                        "v1 allows only \"default\""));
            }
        }

        // fields is optional, default all 4
        Object fieldsObj = props.get("fields");
        if (fieldsObj != null) {
            if (!(fieldsObj instanceof List<?> fieldsList)) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.fields", "fields must be an array"));
                return;
            }

            Set<String> seenFields = new HashSet<>();
            for (int i = 0; i < fieldsList.size(); i++) {
                Object field = fieldsList.get(i);
                if (!(field instanceof String fieldStr)) {
                    errors.add(new ValidationException.FieldError(blockPath + ".props.fields[" + i + "]",
                        "field must be a string"));
                    continue;
                }
                if (!VALID_WEATHER_FIELDS.contains(fieldStr)) {
                    errors.add(new ValidationException.FieldError(blockPath + ".props.fields[" + i + "]",
                        "unknown field: " + fieldStr + ", must be one of " + VALID_WEATHER_FIELDS));
                }
                if (!seenFields.add(fieldStr)) {
                    errors.add(new ValidationException.FieldError(blockPath + ".props.fields[" + i + "]",
                        "duplicate field: " + fieldStr));
                }
            }

            if (fieldsList.isEmpty()) {
                errors.add(new ValidationException.FieldError(blockPath + ".props.fields",
                    "fields must have at least 1 item"));
            }
        }
    }

    private void validateBlockStyle(BlockStyle style, String blockPath, List<ValidationException.FieldError> errors) {
        if (style == null) {
            return;
        }

        if (style.align() != null && !VALID_ALIGN_VALUES.contains(style.align())) {
            errors.add(new ValidationException.FieldError(blockPath + ".style.align",
                "align must be one of " + VALID_ALIGN_VALUES));
        }

        if (style.fontSizePt() != null && (style.fontSizePt() < MIN_FONT_SIZE || style.fontSizePt() > MAX_FONT_SIZE)) {
            errors.add(new ValidationException.FieldError(blockPath + ".style.fontSizePt",
                "fontSizePt must be " + MIN_FONT_SIZE + " to " + MAX_FONT_SIZE));
        }

        if (style.marginTopMm() != null && (style.marginTopMm() < MIN_MARGIN || style.marginTopMm() > MAX_BLOCK_MARGIN)) {
            errors.add(new ValidationException.FieldError(blockPath + ".style.marginTopMm",
                "marginTopMm must be 0 to " + MAX_BLOCK_MARGIN));
        }

        if (style.marginBottomMm() != null && (style.marginBottomMm() < MIN_MARGIN || style.marginBottomMm() > MAX_BLOCK_MARGIN)) {
            errors.add(new ValidationException.FieldError(blockPath + ".style.marginBottomMm",
                "marginBottomMm must be 0 to " + MAX_BLOCK_MARGIN));
        }
    }
}
