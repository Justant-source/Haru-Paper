package com.harupaper.server.widget;

import com.harupaper.server.asset.Asset;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WidgetPropsValidator - kind별 props 검증(.temp/07 3.2절)")
class WidgetPropsValidatorTest {

    // asset kind가 아닌 필드 테스트는 소유권 검사와 무관하므로 고정 요청자를 쓴다.
    private static final String REQUEST_USER_ID = "user-1";

    private AssetRepository assetRepository;
    private WidgetPropsValidator validator;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepository.class);
        validator = new WidgetPropsValidator(assetRepository, false);
    }

    private Widget widgetWithFields(PropField... fields) {
        WidgetDescriptor descriptor = new WidgetDescriptor(
                "test", "테스트", "설명", "text", false, true,
                List.of(WidgetSize.fixed(4, 1, "L")), "4x1", List.of(fields));
        return new Widget() {
            @Override
            public WidgetDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
                return "";
            }
        };
    }

    private List<ValidationException.FieldError> validate(Widget widget, Map<String, Object> props) {
        return validateAs(validator, widget, props, REQUEST_USER_ID);
    }

    private List<ValidationException.FieldError> validateAs(WidgetPropsValidator v, Widget widget,
                                                              Map<String, Object> props, String requestUserId) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        v.validate(widget, props, "widgets[0].props", errors, requestUserId);
        return errors;
    }

    @Test
    @DisplayName("props가 null이면 props 경로 자체가 오류다")
    void nullPropsIsError() {
        Widget widget = widgetWithFields();
        List<ValidationException.FieldError> errors = validate(widget, null);
        assertEquals(1, errors.size());
        assertEquals("widgets[0].props", errors.get(0).path());
    }

    @Test
    @DisplayName("알 수 없는 props 키는 거부한다")
    void unknownKeyRejected() {
        Widget widget = widgetWithFields(PropField.string("name", "이름", false, null, 20, null, null, null));
        List<ValidationException.FieldError> errors = validate(widget, Map.of("bogus", "x"));
        assertTrue(errors.stream().anyMatch(e -> e.path().equals("widgets[0].props.bogus")));
    }

    @Test
    @DisplayName("required 필드가 없거나 빈 문자열이면 오류")
    void requiredMissingOrEmpty() {
        Widget widget = widgetWithFields(PropField.string("name", "이름", true, null, 20, null, null, null));
        assertFalse(validate(widget, Map.of()).isEmpty());
        assertFalse(validate(widget, Map.of("name", "")).isEmpty());
        assertTrue(validate(widget, Map.of("name", "x")).isEmpty());
    }

    @Test
    @DisplayName("string: maxLength·pattern(전체 일치) 검증")
    void stringValidation() {
        Widget widget = widgetWithFields(
                PropField.string("ticker", "티커", true, null, 5, "^[A-Z]{1,5}$", null, null));
        assertFalse(validate(widget, Map.of("ticker", "TOOLONG1")).isEmpty()); // maxLength 5 초과
        assertFalse(validate(widget, Map.of("ticker", "aapl")).isEmpty());     // 소문자는 패턴 위반
        assertTrue(validate(widget, Map.of("ticker", "AAPL")).isEmpty());
    }

    @Test
    @DisplayName("text: pattern은 적용되지 않는다(여러 줄 자유 텍스트)")
    void textIgnoresPattern() {
        Widget widget = widgetWithFields(PropField.text("note", "메모", false, null, 100, null, null));
        assertTrue(validate(widget, Map.of("note", "아무 문장이나 와도 된다")).isEmpty());
    }

    @Test
    @DisplayName("integer: 정수 여부·범위 검증")
    void integerValidation() {
        Widget widget = widgetWithFields(PropField.integer("days", "일수", true, null, 5, 60, null));
        assertFalse(validate(widget, Map.of("days", 3)).isEmpty());     // min 미만
        assertFalse(validate(widget, Map.of("days", 14.5)).isEmpty());  // 정수 아님
        assertFalse(validate(widget, Map.of("days", "14")).isEmpty());  // 숫자 아님
        assertTrue(validate(widget, Map.of("days", 14)).isEmpty());
    }

    @Test
    @DisplayName("boolean: Boolean이 아니면 오류")
    void booleanValidation() {
        Widget widget = widgetWithFields(PropField.bool("bold", "굵게", false, null));
        assertFalse(validate(widget, Map.of("bold", "true")).isEmpty());
        assertTrue(validate(widget, Map.of("bold", true)).isEmpty());
    }

    @Test
    @DisplayName("enum: options[].value 중 하나가 아니면 오류")
    void enumValidation() {
        Widget widget = widgetWithFields(PropField.enumOf("align", "정렬", true, null,
                List.of(new PropField.Option("left", "왼쪽"), new PropField.Option("right", "오른쪽")), null));
        assertFalse(validate(widget, Map.of("align", "center")).isEmpty());
        assertTrue(validate(widget, Map.of("align", "left")).isEmpty());
    }

    @Test
    @DisplayName("koreaLocation: 대한민국 범위 밖 좌표·알 수 없는 키를 거부한다")
    void koreaLocationValidation() {
        Widget widget = widgetWithFields(PropField.koreaLocation("location", "위치", true, null, null));
        assertFalse(validate(widget, Map.of("location",
                Map.of("label", "서울", "lat", 50.0, "lon", 127.0))).isEmpty()); // 위도 범위 밖
        assertFalse(validate(widget, Map.of("location",
                Map.of("label", "서울", "lat", 37.5, "lon", 127.0, "extra", 1))).isEmpty()); // 알 수 없는 키
        assertTrue(validate(widget, Map.of("location",
                Map.of("label", "서울", "lat", 37.5665, "lon", 126.9780))).isEmpty());
    }

    @Test
    @DisplayName("asset: 존재하지 않으면 거부한다")
    void assetMissingRejected() {
        when(assetRepository.findById("missing")).thenReturn(Optional.empty());
        Widget widget = widgetWithFields(PropField.asset("assetId", "이미지", true, null));

        assertFalse(validate(widget, Map.of("assetId", "missing")).isEmpty());
    }

    @Test
    @DisplayName("asset: 요청자 소유면 통과한다")
    void assetOwnedByRequesterPasses() {
        when(assetRepository.findById("a1")).thenReturn(
                Optional.of(Asset.builder().id("a1").ownerUserId(REQUEST_USER_ID).build()));
        Widget widget = widgetWithFields(PropField.asset("assetId", "이미지", true, null));

        assertTrue(validate(widget, Map.of("assetId", "a1")).isEmpty());
    }

    @Test
    @DisplayName("asset: 남의 소유면 존재하지 않는 것처럼 거부한다(IDOR 방지, 2026-09-19)")
    void assetOwnedBySomeoneElseRejected() {
        when(assetRepository.findById("a1")).thenReturn(
                Optional.of(Asset.builder().id("a1").ownerUserId("other-user").build()));
        Widget widget = widgetWithFields(PropField.asset("assetId", "이미지", true, null));

        List<ValidationException.FieldError> errors = validate(widget, Map.of("assetId", "a1"));

        assertFalse(errors.isEmpty());
        // 존재 여부·소유 여부를 구분해 알려주지 않는다 — 메시지가 "not found" 하나로 통일된다.
        assertTrue(errors.get(0).message().contains("asset not found"));
    }

    @Test
    @DisplayName("asset: 소유자 NULL(claim-legacy 전 레거시)은 strict=false면 허용, strict=true면 거부")
    void assetOwnerNullDependsOnStrictFlag() {
        when(assetRepository.findById("legacy")).thenReturn(
                Optional.of(Asset.builder().id("legacy").ownerUserId(null).build()));
        Widget widget = widgetWithFields(PropField.asset("assetId", "이미지", true, null));

        WidgetPropsValidator lenient = new WidgetPropsValidator(assetRepository, false);
        assertTrue(validateAs(lenient, widget, Map.of("assetId", "legacy"), REQUEST_USER_ID).isEmpty());

        WidgetPropsValidator strict = new WidgetPropsValidator(assetRepository, true);
        assertFalse(validateAs(strict, widget, Map.of("assetId", "legacy"), REQUEST_USER_ID).isEmpty());
    }

    @Test
    @DisplayName("asset: requestUserId가 null이면(import 리매핑 전) 소유권 검사를 건너뛰고 존재만 본다")
    void assetSkipsOwnershipWhenRequestUserIdNull() {
        when(assetRepository.findById("a1")).thenReturn(
                Optional.of(Asset.builder().id("a1").ownerUserId("someone-else").build()));
        Widget widget = widgetWithFields(PropField.asset("assetId", "이미지", true, null));

        assertTrue(validateAs(validator, widget, Map.of("assetId", "a1"), null).isEmpty());
    }

    @Test
    @DisplayName("필드 검증을 전부 통과하면 Widget.validateProps가 불린다")
    void callsWidgetValidatePropsOnlyWhenFieldsPass() {
        WidgetDescriptor descriptor = new WidgetDescriptor(
                "test", "테스트", "설명", "text", false, true,
                List.of(WidgetSize.fixed(4, 1, "L")), "4x1",
                List.of(PropField.string("a", "a", false, null, 10, null, null, null)));
        AtomicBoolean called = new AtomicBoolean(false);
        Widget widget = new Widget() {
            @Override
            public WidgetDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void validateProps(Map<String, Object> props, String path, List<ValidationException.FieldError> errors) {
                called.set(true);
            }

            @Override
            public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
                return "";
            }
        };

        validate(widget, Map.of("a", "x"));
        assertTrue(called.get());

        called.set(false);
        validate(widget, Map.of("bogus", "y")); // 필드 검증이 실패하므로 validateProps는 불리지 않는다
        assertFalse(called.get());
    }
}
