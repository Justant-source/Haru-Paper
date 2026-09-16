package com.harupaper.server.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 기기 토큰 생성·해시. DeviceTokenAuthFilter(조회)와 기기 토큰 발급 API(생성) 양쪽이
 * 같은 해시 알고리즘을 써야 하므로 공용 유틸리티로 둔다
 * (.temp/03-플랫폼-작업지시서-v1.0.md 4.3절: "DB엔 SHA-256만").
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHasher() {
    }

    /** 32바이트 난수 → base64url(패딩 없음). 원문은 발급 응답에만 보여주고 저장하지 않는다. */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 소문자 hex 64자. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
