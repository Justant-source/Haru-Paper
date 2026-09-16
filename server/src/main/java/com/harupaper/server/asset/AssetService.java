package com.harupaper.server.asset;

import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.exception.PayloadTooLargeException;
import com.harupaper.server.common.exception.UnsupportedMediaTypeAppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * Asset domain service: upload and retrieve images.
 */
@Slf4j
@Service
@Transactional
public class AssetService {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB
    private static final String PNG_MIME = "image/png";
    private static final String JPEG_MIME = "image/jpeg";

    private final AssetRepository assetRepository;
    private final String filesDir;
    private final long uploadMaxMb;

    public AssetService(AssetRepository assetRepository,
                       @Value("${haru.files-dir}") String filesDir,
                       @Value("${haru.upload-max-mb}") long uploadMaxMb) {
        this.assetRepository = assetRepository;
        this.filesDir = filesDir;
        this.uploadMaxMb = uploadMaxMb;
    }

    /**
     * Upload image file. Returns AssetResponse with metadata.
     */
    public AssetResponse uploadAsset(MultipartFile file) throws IOException {
        // Check file size
        long fileSizeBytes = file.getSize();
        long maxBytes = uploadMaxMb * 1024 * 1024;
        if (fileSizeBytes > maxBytes) {
            throw new PayloadTooLargeException(
                "file size " + fileSizeBytes + " exceeds limit " + maxBytes);
        }

        // Validate MIME type by magic bytes
        byte[] fileBytes = file.getBytes();
        String contentType = validateAndDetermineMime(fileBytes);

        // Get image dimensions
        int[] dimensions = getImageDimensions(fileBytes);

        // Calculate SHA256
        String sha256 = calculateSha256(fileBytes);

        // Create asset entry
        String assetId = UUID.randomUUID().toString();
        String fileExtension = PNG_MIME.equals(contentType) ? "png" : "jpg";
        String filename = assetId + "." + fileExtension;
        String relativePath = "uploads/" + filename;
        Path filePath = Paths.get(filesDir, relativePath);

        // Ensure directory exists and write file
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, fileBytes);

        Asset asset = Asset.builder()
            .id(assetId)
            .contentType(contentType)
            .sizeBytes((int) fileSizeBytes)
            .widthPx(dimensions[0])
            .heightPx(dimensions[1])
            .sha256(sha256)
            .path(relativePath)
            .createdAt(Instant.now())
            .build();

        assetRepository.save(asset);

        return new AssetResponse(
            assetId,
            contentType,
            dimensions[0],
            dimensions[1],
            (int) fileSizeBytes
        );
    }

    /**
     * Get asset by id. Returns AssetFileResponse with file bytes and content type.
     */
    public Asset getAsset(String assetId) {
        return assetRepository.findById(assetId)
            .orElseThrow(() -> new NotFoundException("asset not found: " + assetId));
    }

    /**
     * Get asset file bytes.
     */
    public byte[] getAssetFileBytes(String assetId) throws IOException {
        Asset asset = getAsset(assetId);
        Path filePath = Paths.get(filesDir, asset.getPath());

        if (!Files.exists(filePath)) {
            throw new NotFoundException("asset file not found: " + asset.getPath());
        }

        return Files.readAllBytes(filePath);
    }

    // Helper methods

    /**
     * Validate file by magic bytes and determine MIME type.
     * Only PNG and JPEG are supported.
     */
    private String validateAndDetermineMime(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 4) {
            throw new UnsupportedMediaTypeAppException("file is too small or empty");
        }

        // PNG magic bytes: 89 50 4E 47
        if (fileBytes[0] == (byte) 0x89 && fileBytes[1] == 0x50 && fileBytes[2] == 0x4E && fileBytes[3] == 0x47) {
            return PNG_MIME;
        }

        // JPEG magic bytes: FF D8 FF
        if (fileBytes[0] == (byte) 0xFF && fileBytes[1] == (byte) 0xD8 && fileBytes[2] == (byte) 0xFF) {
            return JPEG_MIME;
        }

        throw new UnsupportedMediaTypeAppException(
            "only image/png and image/jpeg are supported");
    }

    /**
     * Get image dimensions using Java ImageIO.
     */
    private int[] getImageDimensions(byte[] imageBytes) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(imageBytes));
            if (img == null) {
                throw new UnsupportedMediaTypeAppException("failed to read image dimensions");
            }
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            log.error("Failed to get image dimensions", e);
            throw new UnsupportedMediaTypeAppException("failed to read image dimensions", e);
        }
    }

    /**
     * Calculate SHA256 hash of file bytes.
     */
    private String calculateSha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to calculate SHA-256", e);
            throw new RuntimeException("Failed to calculate SHA-256", e);
        }
    }

    /**
     * M6: Upload asset with owner
     */
    public AssetResponse uploadAssetWithOwner(MultipartFile file, String userId) throws IOException {
        // 기존 uploadAsset 로직과 동일하지만 ownerUserId 설정
        long fileSizeBytes = file.getSize();
        long maxBytes = uploadMaxMb * 1024 * 1024;
        if (fileSizeBytes > maxBytes) {
            throw new PayloadTooLargeException(
                "file size " + fileSizeBytes + " exceeds limit " + maxBytes);
        }

        byte[] fileBytes = file.getBytes();
        String contentType = validateAndDetermineMime(fileBytes);
        int[] dimensions = getImageDimensions(fileBytes);
        String sha256 = calculateSha256(fileBytes);

        String assetId = UUID.randomUUID().toString();
        String fileExtension = PNG_MIME.equals(contentType) ? "png" : "jpg";
        String filename = assetId + "." + fileExtension;
        String relativePath = "uploads/" + filename;
        Path filePath = Paths.get(filesDir, relativePath);

        Files.createDirectories(filePath.getParent());
        Files.write(filePath, fileBytes);

        Asset asset = Asset.builder()
            .id(assetId)
            .contentType(contentType)
            .sizeBytes((int) fileSizeBytes)
            .widthPx(dimensions[0])
            .heightPx(dimensions[1])
            .sha256(sha256)
            .path(relativePath)
            .ownerUserId(userId)
            .createdAt(Instant.now())
            .build();

        assetRepository.save(asset);

        return new AssetResponse(
            assetId,
            contentType,
            dimensions[0],
            dimensions[1],
            (int) fileSizeBytes
        );
    }

    /**
     * M6: Get asset with owner check
     */
    public Asset getAssetWithOwnerCheck(String assetId, String userId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new NotFoundException("asset not found: " + assetId));

        // 소유권 확인 (NULL이면 레거시 에셋이므로 거부)
        if (asset.getOwnerUserId() == null || !asset.getOwnerUserId().equals(userId)) {
            throw new NotFoundException("asset not found: " + assetId);
        }

        return asset;
    }

    // DTOs for responses

    public record AssetResponse(
        String assetId,
        String contentType,
        Integer widthPx,
        Integer heightPx,
        Integer sizeBytes
    ) {}
}
