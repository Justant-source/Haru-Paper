package com.harupaper.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 플랫폼 계정. 구독자·위젯 제작자·작가 역할을 전부 겸한다(단일 계정, Q3).
 * handle은 위젯 id(@handle/slug)·작가 URL(/@handle)에 쓰이므로 가입 후 변경 불가
 * (.temp/03-플랫폼-작업지시서-v1.0.md Q28).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private String id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String handle;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(length = 300)
    private String bio;

    /** "user" | "admin" */
    @Column(nullable = false, length = 20)
    private String role;

    /** "active" | "suspended" */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
