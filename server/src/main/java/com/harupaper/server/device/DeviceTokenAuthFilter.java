package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Bearer 토큰 인증 필터 (Pi용).
 * 정확히 4개의 경로만 보호한다:
 * - POST /api/device/poll
 * - GET /api/device/snapshot
 * - GET /api/device/renders/* (정규식으로 매칭)
 * - POST /api/device/results
 *
 * /api/device 와 /api/device/paper-state는 앱용(인증 없음)이므로 이 필터를 통과한다.
 * (docs/server/api.md 3절 경고)
 *
 * DispatcherServlet 이전에 실행되므로 예외를 던져도 GlobalExceptionHandler가 못 잡는다.
 * 401일 때 직접 response에 application/problem+json을 쓴다.
 */
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/device/poll",
            "/api/device/snapshot",
            "/api/device/results"
    );

    private static final String RENDERS_PREFIX = "/api/device/renders/";

    private final String deviceToken;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        // 보호 대상인지 확인
        if (!isProtected(method, requestUri)) {
            // 보호 대상이 아니면 통과
            filterChain.doFilter(request, response);
            return;
        }

        // 토큰 검증
        String authHeader = request.getHeader("Authorization");
        if (!validateToken(authHeader)) {
            sendUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 주어진 경로가 보호 대상인지 확인한다.
     */
    private boolean isProtected(String method, String path) {
        if ("GET".equals(method) && path.startsWith(RENDERS_PREFIX)) {
            return true;
        }

        return PROTECTED_PATHS.contains(path);
    }

    /**
     * Authorization 헤더의 토큰을 검증한다.
     * 헤더가 없거나 형식이 잘못되거나 토큰이 일치하지 않으면 false를 반환한다.
     */
    private boolean validateToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return false;
        }

        if (!authHeader.startsWith("Bearer ")) {
            return false;
        }

        String token = authHeader.substring("Bearer ".length());

        // 상수 시간 비교
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                deviceToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 401 응답을 보낸다 (application/problem+json).
     */
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
        // 필터 종료 시 호출
    }
}
