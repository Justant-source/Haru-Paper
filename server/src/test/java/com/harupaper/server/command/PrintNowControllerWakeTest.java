package com.harupaper.server.command;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.device.Device;
import com.harupaper.server.device.DeviceRepository;
import com.harupaper.server.device.DeviceWakeNotifier;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.render.RenderResult;
import com.harupaper.server.render.RenderService;
import com.harupaper.server.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "지금 인쇄" 명령 생성 후 DeviceWakeNotifier로 Pi의 SSE 연결을 깨우는지 검증
 * (docs/architecture.md 4.3절 — SSE는 신호만, 명령 데이터·소유권 판정은 여전히 poll 경로).
 *
 * DeviceWakeNotifier는 항상 예외를 삼킨다는 설계이므로(DeviceWakeNotifier.wake 내부에서
 * try/catch), 알림 실패가 명령 생성 응답(202)을 막지 않는다는 것을 "정상적으로 예외를 던지지
 * 않는 mock" 케이스로 대신 고정한다 — PrintNowController가 deviceWakeNotifier.wake 호출을
 * 직접 try/catch로 감싸고 있는지는 확실치 않으므로, wake가 예외를 던지는 케이스로 컨트롤러의
 * 방어 로직을 시험하지 않는다.
 */
@DisplayName("PrintNowController - 명령 생성 후 기기 깨우기")
class PrintNowControllerWakeTest {

    private FormatRepository formatRepository;
    private CommandRepository commandRepository;
    private DeviceRepository deviceRepository;
    private RenderService renderService;
    private DeviceWakeNotifier deviceWakeNotifier;
    private PrintNowController controller;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        formatRepository = mock(FormatRepository.class);
        commandRepository = mock(CommandRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        renderService = mock(RenderService.class);
        deviceWakeNotifier = mock(DeviceWakeNotifier.class);

        controller = new PrintNowController(
                formatRepository, commandRepository, deviceRepository, renderService, deviceWakeNotifier
        );

        User user = User.builder()
                .id("user-A")
                .email("a@example.com")
                .passwordHash("irrelevant")
                .handle("usera")
                .displayName("User A")
                .role("user")
                .status("active")
                .mustChangePassword(false)
                .build();
        principal = new UserPrincipal(user);

        Format format = Format.builder()
                .id("format-1")
                .ownerUserId("user-A")
                .name("test-format")
                .schemaVersion(2)
                .body("{}")
                .hasDynamicBlocks(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(formatRepository.findById("format-1")).thenReturn(Optional.of(format));

        RenderResult renderResult = new RenderResult("render-1", new byte[]{1, 2, 3}, "sha", 100, 50, "pbmsha");
        when(renderService.renderForCommand(eq("format-1"), any())).thenReturn(renderResult);

        when(deviceRepository.findByOwnerUserId("user-A")).thenReturn(Optional.empty());

        // save()는 넘어온 엔티티를 그대로 반환 (Command에 id 등 자동 생성 필드가 없으므로 충분)
        when(commandRepository.save(any(Command.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("명령을 저장한 뒤에야 기기를 깨운다 (순서 보장)")
    void printNow_savesCommandBeforeWaking() {
        PrintNowRequestDto request = new PrintNowRequestDto("format-1", true);

        ResponseEntity<PrintNowResponseDto> response = controller.printNow(request, principal);

        assertEquals(202, response.getStatusCode().value());

        InOrder inOrder = inOrder(commandRepository, deviceWakeNotifier);
        inOrder.verify(commandRepository).save(any(Command.class));
        inOrder.verify(deviceWakeNotifier).wake(eq("user-A"), anyString());
    }

    @Test
    @DisplayName("깨우기가 정상 호출돼도(예외 없이) 응답은 여전히 202다")
    void printNow_stillReturns202WhenWakeCalled() {
        PrintNowRequestDto request = new PrintNowRequestDto("format-1", true);

        ResponseEntity<PrintNowResponseDto> response = controller.printNow(request, principal);

        assertEquals(202, response.getStatusCode().value());
        verify(deviceWakeNotifier).wake(eq("user-A"), anyString());
    }
}
