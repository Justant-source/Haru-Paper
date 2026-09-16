package com.harupaper.server.device;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 기기 관리 API 요청/응답 DTO
 */
public class DeviceManagementDto {

    /**
     * POST /api/devices/me/token 응답
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenIssueResponse(
            @JsonProperty("deviceId")
            String deviceId,
            @JsonProperty("token")
            String token,
            @JsonProperty("issuedAt")
            String issuedAt
    ) {
    }

    /**
     * POST /api/devices/pairing-codes 응답
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PairingCodeResponse(
            @JsonProperty("code")
            String code,
            @JsonProperty("expiresAt")
            String expiresAt
    ) {
    }

    /**
     * POST /api/device/pair 요청
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PairRequest(
            @JsonProperty("code")
            String code,
            @JsonProperty("printerProfile")
            String printerProfile
    ) {
    }

    /**
     * POST /api/device/pair 응답
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PairResponse(
            @JsonProperty("deviceId")
            String deviceId,
            @JsonProperty("token")
            String token,
            @JsonProperty("printerProfile")
            String printerProfile
    ) {
    }

    /**
     * GET /api/devices/me 응답
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetDeviceInfoResponse(
            @JsonProperty("deviceId")
            String deviceId,
            @JsonProperty("name")
            String name,
            @JsonProperty("paired")
            boolean paired
    ) {
    }

    /**
     * PATCH /api/devices/me 요청
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateDeviceInfoRequest(
            @JsonProperty("name")
            String name
    ) {
    }
}
