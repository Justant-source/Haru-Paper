package com.harupaper.server.auth;

import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.user.User;
import com.harupaper.server.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PATCH /api/account — 프로필·비밀번호 변경.
 *
 * 원래 AuthController(class-level @RequestMapping("/api/auth")) 안에 있었는데,
 * 그 아래 @PatchMapping("/account")는 /api/auth/account로 매핑돼 work order의
 * 계약(/api/account)과 어긋나는 버그였다. 별도 컨트롤러로 분리해 고쳤다
 * (e2e-smoke.sh 5절에서 이 버그를 잡았다).
 */
@Slf4j
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * PATCH /api/account
     * {displayName?, bio?, currentPassword?, newPassword?}
     * 비밀번호 변경 시 currentPassword 필수.
     */
    @PatchMapping
    public ResponseEntity<AuthController.AuthResponseDto> updateAccount(
            @RequestBody AuthController.AccountUpdateRequestDto request,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = principal.getUser();
        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.newPassword != null && !request.newPassword.isBlank()) {
            if (request.currentPassword == null || request.currentPassword.isBlank()) {
                errors.add(new ValidationException.FieldError("currentPassword",
                        "currentPassword is required to change password"));
            } else if (!passwordEncoder.matches(request.currentPassword, user.getPasswordHash())) {
                errors.add(new ValidationException.FieldError("currentPassword",
                        "current password is incorrect"));
            }

            if (request.newPassword.length() < 10) {
                errors.add(new ValidationException.FieldError("newPassword",
                        "password must be at least 10 characters"));
            }
        }

        if (request.displayName != null && !request.displayName.isBlank()
                && request.displayName.length() > 50) {
            errors.add(new ValidationException.FieldError("displayName",
                    "displayName must be at most 50 characters"));
        }

        if (request.bio != null && request.bio.length() > 300) {
            errors.add(new ValidationException.FieldError("bio",
                    "bio must be at most 300 characters"));
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Account update validation failed", errors);
        }

        if (request.displayName != null && !request.displayName.isBlank()) {
            user.setDisplayName(request.displayName);
        }

        if (request.bio != null) {
            user.setBio(request.bio.isBlank() ? null : request.bio);
        }

        if (request.newPassword != null && !request.newPassword.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.newPassword));
        }

        user.setUpdatedAt(Instant.now());
        User updated = userRepository.save(user);

        log.info("User account updated: {}", user.getHandle());

        return ResponseEntity.ok(AuthController.toAuthResponseDto(updated));
    }
}
