package com.harupaper.server.widget;

import com.harupaper.server.asset.Asset;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 위젯 props의 일반 검증(.temp/07-위젯그리드-작업지시서.md 3.2절) — descriptor.fields 기반으로
 * 타입·범위·필수 여부·알 수 없는 키를 본다. 필드 사이의 관계처럼 스키마로 표현하지 못하는 규칙은
 * 이 검증을 전부 통과한 뒤 {@link Widget#validateProps}가 추가로 본다.
 */
@Slf4j
@Component
public class WidgetPropsValidator {

    // 대한민국 시·군·구 좌표 객체(koreaLocation)가 허용하는 키(PropField.KOREA_LAT_MIN 등 범위 상수는 재사용)
    private static final Set<String> KOREA_LOCATION_KEYS = Set.of("label", "lat", "lon");
    private static final int KOREA_LOCATION_LABEL_MAX_LENGTH = 50;

    private final AssetRepository assetRepository;
    private final boolean ownershipStrict;

    public WidgetPropsValidator(AssetRepository assetRepository,
                                 @Value("${haru.ownership-strict:false}") boolean ownershipStrict) {
        this.assetRepository = assetRepository;
        this.ownershipStrict = ownershipStrict;
    }

    /**
     * @param widget      검증 대상 위젯 구현(descriptor.fields + validateProps 둘 다 여기서 부른다)
     * @param props       widgets[i].props의 값(Map). null이면 그 자체로 오류
     * @param path        오류 경로 접두사(예: "widgets[2].props") — 필드별 오류는 여기에 ".key"를 붙인다
     * @param requestUserId asset 필드 소유권 검사에 쓸 현재 사용자 id. {@code null}이면 소유권 검사를
     *                      건너뛰고 존재 여부만 본다 — import의 리매핑 전 1차 검증처럼 곧 새 에셋으로
     *                      대체될 값을 검증할 때만 의도적으로 null을 넘긴다(FormatController 참고).
     */
    public void validate(Widget widget, Map<String, Object> props, String path,
                          List<ValidationException.FieldError> errors, String requestUserId) {
        if (props == null) {
            errors.add(new ValidationException.FieldError(path, "props is required"));
            return;
        }

        // 이 호출 시작 시점의 오류 개수를 기준으로 삼는다 — 알 수 없는 키 검사까지 포함해서
        // "일반 검증을 전부 통과했을 때만" widget.validateProps를 부르려는 것이다.
        int before = errors.size();

        WidgetDescriptor descriptor = widget.descriptor();
        Set<String> allowedKeys = descriptor.fields().stream()
                .map(PropField::key)
                .collect(Collectors.toSet());
        for (String key : props.keySet()) {
            if (!allowedKeys.contains(key)) {
                errors.add(new ValidationException.FieldError(path + "." + key, "unknown field: " + path + "." + key));
            }
        }

        for (PropField field : descriptor.fields()) {
            validateField(field, props, path, errors, requestUserId);
        }

        // 필드 하나하나의 타입·범위 검증이 전부 통과했을 때만 위젯 고유 규칙을 추가로 본다 —
        // 값 자체가 문자열도 아닌데 정규식·관계 검증을 시도하면 의미 없는 오류가 겹친다.
        if (errors.size() == before) {
            widget.validateProps(props, path, errors);
        }
    }

    private void validateField(PropField field, Map<String, Object> props, String basePath,
                                List<ValidationException.FieldError> errors, String requestUserId) {
        String fieldPath = basePath + "." + field.key();
        Object value = props.get(field.key());
        boolean isEmpty = value == null || (value instanceof String s && s.isEmpty());

        if (field.required() && isEmpty) {
            errors.add(new ValidationException.FieldError(fieldPath, field.key() + " is required"));
            return;
        }
        if (value == null) {
            return;
        }

        switch (field.kind()) {
            case STRING -> validateString(field, value, fieldPath, errors, true);
            case TEXT -> validateString(field, value, fieldPath, errors, false);
            case INTEGER -> validateInteger(field, value, fieldPath, errors);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    errors.add(new ValidationException.FieldError(fieldPath, field.key() + " must be a boolean"));
                }
            }
            case ENUM -> validateEnum(field, value, fieldPath, errors);
            case KOREA_LOCATION -> validateKoreaLocation(value, fieldPath, errors);
            case ASSET -> validateAsset(field, value, fieldPath, errors, requestUserId);
        }
    }

    private void validateString(PropField field, Object value, String path,
                                 List<ValidationException.FieldError> errors, boolean applyPattern) {
        if (!(value instanceof String str)) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be a string"));
            return;
        }
        if (field.maxLength() != null && str.length() > field.maxLength()) {
            errors.add(new ValidationException.FieldError(path,
                    field.key() + " must be 0 to " + field.maxLength() + " characters"));
        }
        // pattern은 STRING kind에서만 적용한다(PropField javadoc) — TEXT(여러 줄)는 정규식 전체
        // 일치가 맞지 않는 용도라 적용하지 않는다.
        if (applyPattern && field.pattern() != null && !str.isEmpty() && !Pattern.matches(field.pattern(), str)) {
            errors.add(new ValidationException.FieldError(path, field.key() + " does not match pattern"));
        }
    }

    private void validateInteger(PropField field, Object value, String path, List<ValidationException.FieldError> errors) {
        if (!(value instanceof Number number)) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be an integer"));
            return;
        }
        double d = number.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be an integer"));
            return;
        }
        int intValue = number.intValue();
        if (field.min() != null && intValue < field.min()) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be >= " + field.min()));
        }
        if (field.max() != null && intValue > field.max()) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be <= " + field.max()));
        }
    }

    private void validateEnum(PropField field, Object value, String path, List<ValidationException.FieldError> errors) {
        if (!(value instanceof String str)) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be a string"));
            return;
        }
        boolean valid = field.options() != null && field.options().stream().anyMatch(o -> o.value().equals(str));
        if (!valid) {
            List<String> allowed = field.options() != null
                    ? field.options().stream().map(PropField.Option::value).toList()
                    : List.of();
            errors.add(new ValidationException.FieldError(path, field.key() + " must be one of " + allowed));
        }
    }

    @SuppressWarnings("unchecked")
    private void validateKoreaLocation(Object value, String path, List<ValidationException.FieldError> errors) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            errors.add(new ValidationException.FieldError(path, "must be an object"));
            return;
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        for (String key : map.keySet()) {
            if (!KOREA_LOCATION_KEYS.contains(key)) {
                errors.add(new ValidationException.FieldError(path + "." + key, "unknown field: " + path + "." + key));
            }
        }

        Object labelObj = map.get("label");
        if (!(labelObj instanceof String label) || label.isEmpty() || label.length() > KOREA_LOCATION_LABEL_MAX_LENGTH) {
            errors.add(new ValidationException.FieldError(path + ".label",
                    "label must be 1 to " + KOREA_LOCATION_LABEL_MAX_LENGTH + " characters"));
        }

        Object latObj = map.get("lat");
        if (!(latObj instanceof Number lat)
                || lat.doubleValue() < PropField.KOREA_LAT_MIN || lat.doubleValue() > PropField.KOREA_LAT_MAX) {
            errors.add(new ValidationException.FieldError(path + ".lat",
                    "lat must be " + PropField.KOREA_LAT_MIN + " to " + PropField.KOREA_LAT_MAX));
        }

        Object lonObj = map.get("lon");
        if (!(lonObj instanceof Number lon)
                || lon.doubleValue() < PropField.KOREA_LON_MIN || lon.doubleValue() > PropField.KOREA_LON_MAX) {
            errors.add(new ValidationException.FieldError(path + ".lon",
                    "lon must be " + PropField.KOREA_LON_MIN + " to " + PropField.KOREA_LON_MAX));
        }
    }

    /**
     * 존재 여부 + 소유권을 함께 본다(2026-09-19 — 이전엔 existsById만 봐서, assetId(uuid)를 알면
     * 남의 이미지를 자기 포맷에 렌더할 수 있었다). 규칙은 DeviceSyncController.assertOwnership과
     * 동일: 소유자 NULL이면 strict일 때만 거부(레거시 에셋, claim-legacy 전), 불일치는 항상 거부.
     * 오류 메시지는 "존재하지 않음"과 "소유가 아님"을 구분하지 않는다 — 존재·소유 여부를 응답으로
     * 알려주지 않으려는 것(NotFoundException과 같은 취지).
     */
    private void validateAsset(PropField field, Object value, String path,
                                List<ValidationException.FieldError> errors, String requestUserId) {
        if (!(value instanceof String assetId)) {
            errors.add(new ValidationException.FieldError(path, field.key() + " must be a string"));
            return;
        }
        Optional<Asset> assetOpt = assetRepository.findById(assetId);
        if (assetOpt.isEmpty()) {
            errors.add(new ValidationException.FieldError(path, "asset not found: " + assetId));
            return;
        }
        if (requestUserId == null) {
            // import 리매핑 전 1차 검증 등 — 소유권 검사를 의도적으로 건너뛰는 호출부(문서 참고)
            return;
        }
        String ownerUserId = assetOpt.get().getOwnerUserId();
        if (ownerUserId == null) {
            if (ownershipStrict) {
                log.error("ASSET_OWNER_NULL asset={} — owner_user_id가 NULL이다. " +
                        "V4 백필/claim-legacy가 끝난 뒤라면 버그다", assetId);
                errors.add(new ValidationException.FieldError(path, "asset not found: " + assetId));
                return;
            }
            log.warn("Asset {} has no owner_user_id (claim-legacy 전 레거시) — strict=false라 허용, " +
                    "요청자={}", assetId, requestUserId);
            return;
        }
        if (!ownerUserId.equals(requestUserId)) {
            errors.add(new ValidationException.FieldError(path, "asset not found: " + assetId));
        }
    }
}
