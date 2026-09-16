package com.harupaper.server.auth;

import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.user.User;
import com.harupaper.server.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M6: 계정 인증 엔드포인트 (.temp/03-플랫폼-작업지시서-v1.0.md 4.1절)
 *
 * - POST /api/auth/signup: 회원가입 {email, password, handle, displayName} → 201
 * - POST /api/auth/login: 로그인 {email, password} → 200 + 세션 쿠키
 * - GET /api/auth/me: 현재 사용자 조회 → {userId, email, handle, displayName, role, ...}
 * - PATCH /api/account: 프로필·비밀번호 변경 {displayName?, bio?, currentPassword?, newPassword?}
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    @Value("${haru.admin-email:}")
    private String adminEmail;

    // 핸들 정규식: 3~20자, 소문자·숫자·하이픈, 시작/끝은 소문자 또는 숫자
    private static final Pattern HANDLE_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,18}[a-z0-9])?$");

    private static final List<String> RESERVED_HANDLES = List.of(
            "haru", "admin", "api", "studio", "market", "auth", "device", "devices"
    );

    /**
     * POST /api/auth/signup
     * 회원가입: {email, password, handle, displayName} → 201 {userId, email, handle, ...}
     *
     * 검증:
     * - 이메일 형식 (RFC 5322 간단한 버전)
     * - 비밀번호 최소 10자
     * - 핸들: 3~20자, [a-z0-9-]만, 시작/끝은 [a-z0-9]
     * - 핸들: 예약어 금지
     * - 이메일·핸들 중복 체크
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponseDto> signup(
            @RequestBody SignupRequestDto request) {

        List<ValidationException.FieldError> errors = new ArrayList<>();

        // 이메일 검증
        if (request.email == null || request.email.isBlank()) {
            errors.add(new ValidationException.FieldError("email", "email is required"));
        } else if (!isValidEmail(request.email)) {
            errors.add(new ValidationException.FieldError("email", "invalid email format"));
        } else if (userRepository.existsByEmail(request.email)) {
            errors.add(new ValidationException.FieldError("email", "email already registered"));
        }

        // 비밀번호 검증
        if (request.password == null || request.password.isBlank()) {
            errors.add(new ValidationException.FieldError("password", "password is required"));
        } else if (request.password.length() < 10) {
            errors.add(new ValidationException.FieldError("password", "password must be at least 10 characters"));
        }

        // 핸들 검증
        if (request.handle == null || request.handle.isBlank()) {
            errors.add(new ValidationException.FieldError("handle", "handle is required"));
        } else {
            String handle = request.handle.toLowerCase().trim();
            if (!HANDLE_PATTERN.matcher(handle).matches()) {
                errors.add(new ValidationException.FieldError("handle",
                        "handle must be 3-20 characters, lowercase letters/numbers/hyphens, start and end with letter or number"));
            } else if (RESERVED_HANDLES.contains(handle)) {
                errors.add(new ValidationException.FieldError("handle", "handle is reserved"));
            } else if (userRepository.existsByHandle(handle)) {
                errors.add(new ValidationException.FieldError("handle", "handle already taken"));
            }
        }

        // displayName 검증
        if (request.displayName == null || request.displayName.isBlank()) {
            errors.add(new ValidationException.FieldError("displayName", "displayName is required"));
        } else if (request.displayName.length() > 50) {
            errors.add(new ValidationException.FieldError("displayName", "displayName must be at most 50 characters"));
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Signup validation failed", errors);
        }

        // 사용자 생성
        String userId = UUID.randomUUID().toString();
        String handle = request.handle.toLowerCase().trim();
        String passwordHash = passwordEncoder.encode(request.password);
        String role = adminEmail != null && adminEmail.equals(request.email) ? "admin" : "user";
        Instant now = Instant.now();

        User newUser = User.builder()
                .id(userId)
                .email(request.email)
                .passwordHash(passwordHash)
                .handle(handle)
                .displayName(request.displayName)
                .bio(null)
                .role(role)
                .status("active")
                .mustChangePassword(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        User saved = userRepository.save(newUser);
        log.info("User registered: {} ({})", handle, userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(toAuthResponseDto(saved));
    }

    /**
     * POST /api/auth/login
     * 로그인: {email, password} → 200 + 세션 쿠키
     *
     * 처리:
     * 1. AuthenticationManager로 인증
     * 2. SecurityContext에 저장
     * 3. 세션에 저장 (SecurityContextRepository.saveContext)
     * 4. 200 응답 {userId, email, handle, ...}
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(
            @RequestBody LoginRequestDto request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.email == null || request.email.isBlank()) {
            errors.add(new ValidationException.FieldError("email", "email is required"));
        }

        if (request.password == null || request.password.isBlank()) {
            errors.add(new ValidationException.FieldError("password", "password is required"));
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Login validation failed", errors);
        }

        // AuthenticationManager로 인증 (이메일로 조회해서 비밀번호 검증)
        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(request.email, request.password);
            Authentication authResult = authenticationManager.authenticate(token);

            // SecurityContext 저장
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authResult);
            SecurityContextHolder.setContext(context);

            // JDBC 세션에 저장 (요청·응답 쌍으로 호출해야 함)
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            // UserPrincipal에서 User 가져오기
            UserPrincipal principal = (UserPrincipal) authResult.getPrincipal();
            User user = principal.getUser();

            log.info("User logged in: {} ({})", user.getHandle(), user.getId());

            return ResponseEntity.ok(toAuthResponseDto(user));
        } catch (org.springframework.security.core.AuthenticationException e) {
            log.warn("Login failed for email: {}", request.email);
            throw new ValidationException("Login failed", List.of(
                    new ValidationException.FieldError("email", "invalid email or password")
            ));
        }
    }

    /**
     * GET /api/auth/me
     * 현재 사용자 조회 (로그인 필수 — SecurityConfig에서 401 처리)
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponseDto> getCurrentUser(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(toAuthResponseDto(principal.getUser()));
    }

    // Helper methods

    private boolean isValidEmail(String email) {
        // 간단한 이메일 검증 (RFC 5322의 간단한 버전)
        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        return Pattern.matches(emailRegex, email);
    }

    /** AccountController(별도 클래스, /api/account)도 같은 응답 DTO를 써서 여기 static으로 둔다. */
    static AuthResponseDto toAuthResponseDto(User user) {
        return new AuthResponseDto(
                user.getId(),
                user.getEmail(),
                user.getHandle(),
                user.getDisplayName(),
                user.getBio(),
                user.getRole(),
                user.getStatus(),
                user.getMustChangePassword()
        );
    }

    // DTO classes

    public static class SignupRequestDto {
        public String email;
        public String password;
        public String handle;
        public String displayName;

        public SignupRequestDto() {}

        public SignupRequestDto(String email, String password, String handle, String displayName) {
            this.email = email;
            this.password = password;
            this.handle = handle;
            this.displayName = displayName;
        }
    }

    public static class LoginRequestDto {
        public String email;
        public String password;

        public LoginRequestDto() {}

        public LoginRequestDto(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static class AccountUpdateRequestDto {
        public String displayName;
        public String bio;
        public String currentPassword;
        public String newPassword;

        public AccountUpdateRequestDto() {}
    }

    public static class AuthResponseDto {
        public String userId;
        public String email;
        public String handle;
        public String displayName;
        public String bio;
        public String role;
        public String status;
        public Boolean mustChangePassword;

        public AuthResponseDto(String userId, String email, String handle, String displayName,
                               String bio, String role, String status, Boolean mustChangePassword) {
            this.userId = userId;
            this.email = email;
            this.handle = handle;
            this.displayName = displayName;
            this.bio = bio;
            this.role = role;
            this.status = status;
            this.mustChangePassword = mustChangePassword;
        }
    }
}
