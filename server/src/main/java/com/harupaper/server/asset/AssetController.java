package com.harupaper.server.asset;

import com.harupaper.server.auth.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * M6: Asset REST endpoints (사용자 인증 필수).
 * 소유권 스코핑: 업로드 시 owner_user_id 설정, 조회는 소유자만
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    /**
     * POST /api/assets - Upload image file
     * Form parameter: file (multipart/form-data)
     */
    @PostMapping
    public ResponseEntity<AssetService.AssetResponse> uploadAsset(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserPrincipal principal) throws IOException {

        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        AssetService.AssetResponse response = assetService.uploadAssetWithOwner(file, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/assets/{assetId} - Download asset file (owner only or 404)
     */
    @GetMapping("/{assetId}")
    public ResponseEntity<byte[]> getAsset(
            @PathVariable String assetId,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        Asset asset = assetService.getAssetWithOwnerCheck(assetId, userId);
        byte[] fileBytes = assetService.getAssetFileBytes(assetId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(asset.getContentType()));
        headers.setCacheControl(CacheControl.maxAge(86400, TimeUnit.SECONDS).cachePrivate());
        headers.setContentLength(fileBytes.length);

        return ResponseEntity.ok()
            .headers(headers)
            .body(fileBytes);
    }
}
