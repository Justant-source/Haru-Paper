package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.common.security.TokenHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Bearer 토큰 인증 필터 (Pi용). M6부터 기기별 토큰을 DB에서 조회한다(해시로 저장,
 * .temp/03-플랫폼-작업지시서-v1.0.md 4.3절) — 서버 .env의 단일 토큰은 더 이상 없다.
 *
 * 정확히 4개의 경로만 보호한다:
 * - POST /api/device/poll
 * - GET /api/device/snapshot
 * - GET /api/device/renders/* (정규식으로 매칭)
 * - POST /api/device/results
 *
 * /api/device, /api/device/paper-state, /api/device/pair는 인증이 다르므로(앱: 무인증,
 * pair: 코드 자체가 1회용 비밀) 이 필터를 통과한다 (docs/server/api.md 3절 경고).
 *
 * 인증에 성공하면 resolved Device를 request attribute {@link #DEVICE_ATTRIBUTE}에 담아
 * DeviceSyncController/DeviceSyncService가 어떤 기기·소유자의 요청인지 알 수 있게 한다.
 *
 * DispatcherServlet 이전에 실행되므로 예외를 던져도 GlobalExceptionHandler가 못 잡는다.
 * 401일 때 직접 response에 application/problem+json을 쓴다.
 */
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenAuthFilter extends OncePerRequestFilter {

    public static final String DEVICE_ATTRIBUTE = "haru.device";

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/device/poll",
            "/api/device/snapshot",
            "/api/device/results",
            "/api/device/events"
    );

    private static final String RENDERS_PREFIX = "/api/device/renders/";

    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        if (!isProtected(method, requestUri)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<Device> device = resolveDevice(request.getHeader("Authorization"));
        if (device.isEmpty()) {
            sendUnauthorized(response);
            return;
        }

        request.setAttribute(DEVICE_ATTRIBUTE, device.get());
        filterChain.doFilter(request, response);
    }

    private boolean isProtected(String method, String path) {
        if ("GET".equals(method) && path.startsWith(RENDERS_PREFIX)) {
            return true;
        }
        return PROTECTED_PATHS.contains(path);
    }

    /**
     * Authorization 헤더의 토큰을 해시해 DB에서 조회한다.
     */
    private Optional<Device> resolveDevice(String authHeader) {
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }

        String token = authHeader.substring("Bearer ".length());
        if (token.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = TokenHasher.sha256Hex(token);
        return deviceRepository.findByTokenHash(tokenHash);
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Invalid or missing Authorization header");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");

        String json = objectMapper.writeValueAsString(problem);
        response.getWriter().write(json);
    }

    @Override
    public void destroy() {
    }
}
