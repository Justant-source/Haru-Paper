package com.harupaper.server.asset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Asset REST endpoints (app-facing, no authentication).
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
        @RequestParam("file") MultipartFile file) throws IOException {

        AssetService.AssetResponse response = assetService.uploadAsset(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/assets/{assetId} - Download asset file
     */
    @GetMapping("/{assetId}")
    public ResponseEntity<byte[]> getAsset(@PathVariable String assetId) throws IOException {
        Asset asset = assetService.getAsset(assetId);
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
