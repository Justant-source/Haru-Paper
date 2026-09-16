package com.harupaper.server.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * M6 세션 인증 (.temp/03-플랫폼-작업지시서-v1.0.md 4.1절, Q13).
 *
 * - 세션은 JDBC에 저장(spring-session-jdbc, V2 마이그레이션의 SPRING_SESSION* 테이블)
 * - CSRF는 쿠키 기반 더블서밋(CookieCsrfTokenRepository.withHttpOnlyFalse()) — SPA가
 *   XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 되돌려 보낸다.
 *   **주의 2개** (둘 다 안 하면 curl/JS가 쿠키 값을 그대로 보내도 403이 난다):
 *   ① 기본 요청 핸들러(XorCsrfTokenRequestAttributeHandler)는 헤더 값을 XOR로 마스킹된 것으로
 *      기대한다 — 쿠키에는 원문이 그대로 들어가므로 반드시 평문 {@link CsrfTokenRequestAttributeHandler}로
 *      바꿔야 "쿠키 값을 그대로 헤더에 넣어 보낸다"는 SPA 패턴이 성립한다
 *   ② CookieCsrfTokenRepository는 지연 로딩이라, 아무도 CsrfToken을 안 읽으면 쿠키 자체가
 *      응답에 안 실린다 — GET 요청에서도 토큰을 강제로 읽어 쿠키를 심는 필터가 있어야 한다
 *      (Spring 공식 "Integrating CSRF Protection" 문서의 SPA 절 그대로)
 * - Pi가 쓰는 /api/device/{poll,snapshot,renders/**,results}는 Bearer 토큰으로 별도 인증되므로
 *   (DeviceTokenAuthFilter, order=1, 이 필터 체인보다 먼저 실행되지 않고 뒤에 실행되지만 세션 인증과는
 *   무관하다) 여기서는 permitAll + CSRF 제외로 둔다. /api/device/pair도 1회용 코드가 인증이므로 동일.
 * - 그 외 /api/** 는 로그인이 있어야 한다. 401/403은 리다이렉트 대신 ProblemDetail JSON으로 응답한다
 *   (앱이 SPA라 로그인 페이지로 서버가 리다이렉트하면 안 된다).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    private static final String[] DEVICE_BEARER_PATHS = {
            "/api/device/poll",
            "/api/device/snapshot",
            "/api/device/renders/**",
            "/api/device/results",
            "/api/device/pair"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(DEVICE_BEARER_PATHS))
                .addFilterAfter(csrfCookieFilter(), CsrfFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/auth/signup", "/api/auth/login").permitAll()
                        .requestMatchers(DEVICE_BEARER_PATHS).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeProblem(response, HttpStatus.UNAUTHORIZED, "로그인이 필요하다"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeProblem(response, HttpStatus.FORBIDDEN, "권한이 없다")))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout"))
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .deleteCookies("JSESSIONID"));

        return http.build();
    }

    /**
     * CsrfFilter가 지연 로딩하는 CsrfToken을 모든 요청에서 강제로 읽어 쿠키에 실제로
     * 쓰이게 만든다. 이게 없으면 첫 GET(예: 앱 부팅 시 /api/auth/me)에 XSRF-TOKEN 쿠키가
     * 안 실려서, 그 다음 POST(로그인·가입)가 CSRF 토큰 없이 나가 403이 난다.
     */
    private static OncePerRequestFilter csrfCookieFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (csrfToken != null) {
                    csrfToken.getToken();
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    private void writeProblem(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String detail)
            throws java.io.IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(pd));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * AuthController(로그인)가 이 빈으로 인증 성공을 세션에 직접 저장한다
     * (formLogin을 쓰지 않고 JSON 로그인 응답을 돌려줘야 해서 수동으로 배선한다):
     * {@code
     *   Authentication authResult = authenticationManager.authenticate(token);
     *   SecurityContext context = SecurityContextHolder.createEmptyContext();
     *   context.setAuthentication(authResult);
     *   SecurityContextHolder.setContext(context);
     *   securityContextRepository.saveContext(context, request, response);
     * }
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
