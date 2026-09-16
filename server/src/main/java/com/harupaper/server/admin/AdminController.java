package com.harupaper.server.admin;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.user.User;
import com.harupaper.server.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * M6: 관리자 API (.temp/03-플랫폼-작업지시서-v1.0.md 4.2절)
 *
 * - GET /api/admin/users: 사용자 목록
 * - POST /api/admin/users/{id}/temp-password: 임시 비밀번호 생성
 * - POST /api/admin/users/{id}/suspend: 계정 정지/활성화 토글
 * - POST /api/admin/claim-legacy: 레거시 리소스(owner_user_id=NULL) 소유권 이전
 *
 * SecurityConfig에서 .requestMatchers("/api/admin/**").hasRole("ADMIN") 필수.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminService adminService;

    /**
     * GET /api/admin/users
     * 사용자 목록 조회 (관리자 전용)
     */
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserDto>> listUsers(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<User> users = userRepository.findAll();
        List<AdminUserDto> dtos = users.stream()
                .map(this::toAdminUserDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * POST /api/admin/users/{id}/temp-password
     * 사용자에게 임시 비밀번호 발급
     * 응답: {tempPassword} (평문, 1회만 반환)
     * 저장된 것은 해시이고, mustChangePassword=true로 설정
     */
    @PostMapping("/users/{id}/temp-password")
    public ResponseEntity<TempPasswordResponse> generateTempPassword(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // 임시 비밀번호 생성 (8자 랜덤)
        String tempPassword = generateRandomPassword();
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        user.setUpdatedAt(Instant.now());

        userRepository.save(user);

        log.info("Temporary password generated for user: {}", user.getHandle());

        return ResponseEntity.ok(new TempPasswordResponse(tempPassword));
    }

    /**
     * POST /api/admin/users/{id}/suspend
     * 사용자 계정 정지/활성화 토글 (또는 body로 status 지정)
     * body (선택): {status: "active" | "suspended"}
     */
    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<AdminUserDto> toggleSuspend(
            @PathVariable String id,
            @RequestBody(required = false) SuspendRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // status 변경 (토글 또는 지정)
        if (request != null && request.status != null) {
            if ("active".equals(request.status) || "suspended".equals(request.status)) {
                user.setStatus(request.status);
            }
        } else {
            // 토글
            String newStatus = "active".equals(user.getStatus()) ? "suspended" : "active";
            user.setStatus(newStatus);
        }

        user.setUpdatedAt(Instant.now());
        User updated = userRepository.save(user);

        log.info("User status changed: {} -> {}", user.getHandle(), updated.getStatus());

        return ResponseEntity.ok(toAdminUserDto(updated));
    }

    /**
     * POST /api/admin/claim-legacy
     * 현재 로그인한 관리자가 owner_user_id=NULL인 모든 리소스를 자신의 것으로 가져온다
     * 응답: {updatedFormatCount, updatedScheduleCount, updatedAssetCount, updatedRenderCount, updatedCommandCount, updatedResultCount}
     */
    @PostMapping("/claim-legacy")
    public ResponseEntity<ClaimLegacyResponse> claimLegacy(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String adminUserId = principal.userId();
        ClaimLegacyResponse response = adminService.claimLegacyResources(adminUserId);

        log.info("Legacy resources claimed by admin {}: {}", adminUserId, response);

        return ResponseEntity.ok(response);
    }

    // Helper methods

    private String generateRandomPassword() {
        // 8자 랜덤 비밀번호 (대문자, 소문자, 숫자, 기호 포함)
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            password.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return password.toString();
    }

    private AdminUserDto toAdminUserDto(User user) {
        return new AdminUserDto(
                user.getId(),
                user.getEmail(),
                user.getHandle(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getMustChangePassword()
        );
    }

    // DTO classes

    public static class AdminUserDto {
        public String id;
        public String email;
        public String handle;
        public String displayName;
        public String role;
        public String status;
        public Boolean mustChangePassword;

        public AdminUserDto(String id, String email, String handle, String displayName,
                           String role, String status, Boolean mustChangePassword) {
            this.id = id;
            this.email = email;
            this.handle = handle;
            this.displayName = displayName;
            this.role = role;
            this.status = status;
            this.mustChangePassword = mustChangePassword;
        }
    }

    public static class TempPasswordResponse {
        public String tempPassword;

        public TempPasswordResponse(String tempPassword) {
            this.tempPassword = tempPassword;
        }
    }

    public static class SuspendRequest {
        public String status;

        public SuspendRequest() {}

        public SuspendRequest(String status) {
            this.status = status;
        }
    }

    public static class ClaimLegacyResponse {
        public int updatedFormatCount;
        public int updatedScheduleCount;
        public int updatedAssetCount;
        public int updatedRenderCount;
        public int updatedCommandCount;
        public int updatedResultCount;

        public ClaimLegacyResponse(int formats, int schedules, int assets, int renders, int commands, int results) {
            this.updatedFormatCount = formats;
            this.updatedScheduleCount = schedules;
            this.updatedAssetCount = assets;
            this.updatedRenderCount = renders;
            this.updatedCommandCount = commands;
            this.updatedResultCount = results;
        }

        @Override
        public String toString() {
            return String.format("ClaimLegacy{formats=%d, schedules=%d, assets=%d, renders=%d, commands=%d, results=%d}",
                    updatedFormatCount, updatedScheduleCount, updatedAssetCount,
                    updatedRenderCount, updatedCommandCount, updatedResultCount);
        }
    }
}
