package com.harupaper.server.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.asset.Asset;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.common.exception.ConflictException;
import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.exception.PayloadTooLargeException;
import com.harupaper.server.common.exception.UnsupportedMediaTypeAppException;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.render.RenderScanTrigger;
import com.harupaper.server.schedule.ScheduleRepository;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Format domain service: create, read, update, delete, import, export.
 */
@Slf4j
@Service
@Transactional
public class FormatService {

    private final FormatRepository formatRepository;
    private final AssetRepository assetRepository;
    private final ScheduleRepository scheduleRepository;
    private final FormatValidator validator;
    private final ObjectMapper objectMapper;
    private final RenderScanTrigger renderScanTrigger;
    private final WidgetRegistry widgetRegistry;
    private final String filesDir;
    private final long uploadMaxMb;

    public FormatService(FormatRepository formatRepository,
                        AssetRepository assetRepository,
                        ScheduleRepository scheduleRepository,
                        FormatValidator validator,
                        ObjectMapper objectMapper,
                        RenderScanTrigger renderScanTrigger,
                        WidgetRegistry widgetRegistry,
                        @Value("${haru.files-dir}") String filesDir,
                        @Value("${haru.upload-max-mb:10}") long uploadMaxMb) {
        this.formatRepository = formatRepository;
        this.assetRepository = assetRepository;
        this.scheduleRepository = scheduleRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.renderScanTrigger = renderScanTrigger;
        this.widgetRegistry = widgetRegistry;
        this.filesDir = filesDir;
        this.uploadMaxMb = uploadMaxMb;
    }

    /**
     * Get format by id.
     */
    public Format getFormat(String id) {
        return formatRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("format not found: " + id));
    }

    /**
     * Import format from JSON with embedded assets.
     * Returns new format with forkedFrom set.
     */
    public Format importFormat(FormatDocumentWithAssets importedData, String userId) throws IOException {
        // FormatController가 검증(validateAndParse, schemaVersion 3만 허용) 전에 이미
        // FormatDocumentSupport.upConvertRawImportIfNeeded로 v1·v2→v3를 끝냈다 — 여기 도달하는
        // document는 항상 schemaVersion 3다.
        if (importedData.document().schemaVersion() != 3) {
            throw new com.harupaper.server.common.exception.ValidationException(
                "unsupported schemaVersion",
                List.of(new com.harupaper.server.common.exception.ValidationException.FieldError(
                    "schemaVersion",
                    "unsupported schemaVersion: " + importedData.document().schemaVersion() + " (only 3 supported)"
                ))
            );
        }

        requireEmbeddedAssets(importedData.document(), importedData.assets());

        // Decode and save assets, build assetId mapping
        Map<String, String> assetIdMapping = new HashMap<>();
        if (importedData.assets() != null && !importedData.assets().isEmpty()) {
            for (Map.Entry<String, String> entry : importedData.assets().entrySet()) {
                String oldAssetId = entry.getKey();
                String dataUri = entry.getValue();
                String newAssetId = decodeAndSaveAsset(dataUri, userId);
                assetIdMapping.put(oldAssetId, newAssetId);
            }
        }

        // Remap assetIds in document
        FormatDocument remappedDoc = remapAssetIds(importedData.document(), assetIdMapping);

        // Create forkedFrom metadata
        FormatMeta originalMeta = importedData.document().meta();
        ForkedFrom forkedFrom = new ForkedFrom(
            originalMeta.name(),
            originalMeta.author() != null ? originalMeta.author() : "",
            importedData.document().schemaVersion(),
            TimeUtils.toIso8601(Instant.now())
        );

        FormatDocument withForkedFrom = new FormatDocument(
            remappedDoc.schemaVersion(),
            new FormatMeta(
                remappedDoc.meta().name(),
                remappedDoc.meta().author(),
                remappedDoc.meta().description(),
                forkedFrom
            ),
            remappedDoc.style(),
            remappedDoc.widgets()
        );

        // Validate and create format
        validator.validate(withForkedFrom);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Format format = Format.builder()
            .id(id)
            .name(withForkedFrom.meta().name())
            .schemaVersion(withForkedFrom.schemaVersion())
            .body(serializeDocument(withForkedFrom))
            .hasDynamicBlocks(widgetRegistry.hasDynamic(withForkedFrom.widgets()))
            .ownerUserId(userId)
            .createdAt(now)
            .updatedAt(now)
            .build();

        Format saved = formatRepository.save(format);
        renderScanTrigger.requestScan(userId);
        return saved;
    }

    /**
     * Export format with embedded assets as data URIs.
     */
    public FormatDocumentWithAssets exportFormat(String id) throws IOException {
        Format format = getFormat(id);
        FormatDocument doc = deserializeDocument(format.getBody());

        Map<String, String> assets = new HashMap<>();
        if (doc.widgets() != null) {
            for (WidgetInstance widget : doc.widgets()) {
                if ("image".equals(widget.type()) && widget.props() != null) {
                    Object assetIdObj = widget.props().get("assetId");
                    if (assetIdObj instanceof String assetId && !assets.containsKey(assetId)) {
                        Asset asset = assetRepository.findById(assetId)
                            .orElseThrow(() -> new NotFoundException("asset not found: " + assetId));
                        String dataUri = assetToDataUri(asset);
                        assets.put(assetId, dataUri);
                    }
                }
            }
        }

        return new FormatDocumentWithAssets(doc, assets);
    }

    /**
     * Get FormatDocument from Format entity (deserialize from JSON body).
     */
    public FormatDocument getFormatDocument(Format format) {
        return deserializeDocument(format.getBody());
    }

    /**
     * Get validator instance (used by controller for ephemeral preview validation).
     */
    public FormatValidator getFormatValidator() {
        return validator;
    }

    // Helper methods

    private String serializeDocument(FormatDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (Exception e) {
            log.error("Failed to serialize format document", e);
            throw new RuntimeException("Failed to serialize format document", e);
        }
    }

    private FormatDocument deserializeDocument(String body) {
        // 저장된 v1 포맷은 자동으로 up-convert된다
        return FormatDocumentSupport.readDocument(body, objectMapper);
    }

    private FormatDocument normalizeDocument(FormatDocument document) {
        return new FormatDocument(
            document.schemaVersion(),
            document.meta(),
            FormatStyle.withDefaults(document.style()),
            document.widgets()
        );
    }

    private String decodeAndSaveAsset(String dataUri, String userId) throws IOException {
        // Parse data URI: data:image/png;base64,<encoded>
        if (!dataUri.startsWith("data:")) {
            throw new UnsupportedMediaTypeAppException("invalid data URI format");
        }

        int commaIndex = dataUri.indexOf(',');
        if (commaIndex == -1) {
            throw new UnsupportedMediaTypeAppException("invalid data URI format");
        }

        String mimeAndEncoding = dataUri.substring(5, commaIndex);
        String encodedData = dataUri.substring(commaIndex + 1);

        String mimeType = mimeAndEncoding.replace(";base64", "").trim();
        if (!("image/png".equals(mimeType) || "image/jpeg".equals(mimeType))) {
            throw new UnsupportedMediaTypeAppException("only image/png and image/jpeg are supported");
        }

        byte[] imageBytes = Base64.getDecoder().decode(encodedData);
        long maxBytes = uploadMaxMb * 1024 * 1024;
        if (imageBytes.length > maxBytes) {
            throw new PayloadTooLargeException(
                    "embedded asset size " + imageBytes.length + " exceeds limit " + maxBytes);
        }

        // Create Asset entry and save file
        String assetId = UUID.randomUUID().toString();
        String fileExtension = "image/png".equals(mimeType) ? "png" : "jpg";
        String filename = assetId + "." + fileExtension;
        String relativePath = "uploads/" + filename;
        Path filePath = Paths.get(filesDir, relativePath);

        // Ensure directory exists
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, imageBytes);

        // Get image dimensions
        int[] dimensions = getImageDimensions(imageBytes);

        // Calculate SHA256
        String sha256 = calculateSha256(imageBytes);

        Asset asset = Asset.builder()
            .id(assetId)
            .ownerUserId(userId)
            .contentType(mimeType)
            .sizeBytes(imageBytes.length)
            .widthPx(dimensions[0])
            .heightPx(dimensions[1])
            .sha256(sha256)
            .path(relativePath)
            .createdAt(Instant.now())
            .build();

        assetRepository.save(asset);
        return assetId;
    }

    private String assetToDataUri(Asset asset) throws IOException {
        Path filePath = Paths.get(filesDir, asset.getPath());
        byte[] imageBytes = Files.readAllBytes(filePath);
        String encodedData = Base64.getEncoder().encodeToString(imageBytes);
        return "data:" + asset.getContentType() + ";base64," + encodedData;
    }

    private int[] getImageDimensions(byte[] imageBytes) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            if (img == null) {
                throw new UnsupportedMediaTypeAppException("failed to read image dimensions");
            }
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            log.error("Failed to get image dimensions", e);
            throw new UnsupportedMediaTypeAppException("failed to read image dimensions", e);
        }
    }

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

    private void requireEmbeddedAssets(FormatDocument document, Map<String, String> assets) {
        Map<String, String> embedded = assets != null ? assets : Map.of();
        List<com.harupaper.server.common.exception.ValidationException.FieldError> errors = new java.util.ArrayList<>();
        if (document.widgets() == null) {
            return;
        }
        for (int i = 0; i < document.widgets().size(); i++) {
            WidgetInstance widget = document.widgets().get(i);
            if (!"image".equals(widget.type()) || widget.props() == null) {
                continue;
            }
            Object assetIdObj = widget.props().get("assetId");
            if (assetIdObj instanceof String assetId && !embedded.containsKey(assetId)) {
                errors.add(new com.harupaper.server.common.exception.ValidationException.FieldError(
                        "widgets[" + i + "].props.assetId",
                        "assetId must be present in assets (reissued on import)"));
            }
        }
        if (!errors.isEmpty()) {
            throw new com.harupaper.server.common.exception.ValidationException(
                    "format document is invalid", errors);
        }
    }

    private FormatDocument remapAssetIds(FormatDocument document, Map<String, String> assetIdMapping) {
        if (assetIdMapping.isEmpty()) {
            return document;
        }

        List<WidgetInstance> remappedWidgets = document.widgets().stream().map(widget -> {
            if ("image".equals(widget.type()) && widget.props() != null) {
                Object assetIdObj = widget.props().get("assetId");
                if (assetIdObj instanceof String oldAssetId) {
                    String newAssetId = assetIdMapping.get(oldAssetId);
                    if (newAssetId != null) {
                        Map<String, Object> newProps = new HashMap<>(widget.props());
                        newProps.put("assetId", newAssetId);
                        return new WidgetInstance(widget.id(), widget.type(), widget.size(), newProps);
                    }
                }
            }
            return widget;
        }).toList();

        return new FormatDocument(
            document.schemaVersion(),
            document.meta(),
            document.style(),
            remappedWidgets
        );
    }

    private ForkedFrom getForkedFromFromBody(String body) {
        try {
            FormatDocument doc = deserializeDocument(body);
            return doc.meta() != null ? doc.meta().forkedFrom() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * M6: 사용자별 포맷 목록 조회
     */
    public List<Format> listFormatsByOwner(String userId) {
        return formatRepository.findByOwnerUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * M6: 포맷 생성 (소유권 설정)
     */
    public Format createFormatWithOwner(FormatDocument document, String userId) {
        validator.validate(document);

        FormatDocument normalized = normalizeDocument(document);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Format format = Format.builder()
            .id(id)
            .name(normalized.meta().name())
            .schemaVersion(normalized.schemaVersion())
            .body(serializeDocument(normalized))
            .hasDynamicBlocks(widgetRegistry.hasDynamic(normalized.widgets()))
            .ownerUserId(userId)
            .createdAt(now)
            .updatedAt(now)
            .build();

        Format saved = formatRepository.save(format);
        renderScanTrigger.requestScan(userId);
        return saved;
    }

    /**
     * M6: 포맷 조회 (소유권 검증)
     */
    public Format getFormatWithOwnerCheck(String id, String userId) {
        Format format = getFormat(id);
        if (format.getOwnerUserId() == null || !format.getOwnerUserId().equals(userId)) {
            throw new NotFoundException("format not found: " + id);
        }
        return format;
    }

    /**
     * M6: 포맷 수정 (소유권 검증)
     */
    public Format updateFormatWithOwnerCheck(String id, FormatDocument document, String userId) {
        Format existing = getFormatWithOwnerCheck(id, userId);
        validator.validate(document);

        FormatDocument normalized = normalizeDocument(document);
        // Preserve forkedFrom from existing
        FormatDocument withPreservedForkedFrom = new FormatDocument(
            normalized.schemaVersion(),
            new FormatMeta(
                normalized.meta().name(),
                normalized.meta().author(),
                normalized.meta().description(),
                existing.getBody() != null ? getForkedFromFromBody(existing.getBody()) : null
            ),
            normalized.style(),
            normalized.widgets()
        );

        existing.setName(withPreservedForkedFrom.meta().name());
        existing.setSchemaVersion(withPreservedForkedFrom.schemaVersion());
        existing.setBody(serializeDocument(withPreservedForkedFrom));
        existing.setHasDynamicBlocks(widgetRegistry.hasDynamic(withPreservedForkedFrom.widgets()));
        existing.setUpdatedAt(Instant.now());

        Format saved = formatRepository.save(existing);
        renderScanTrigger.requestScan(userId);
        return saved;
    }

    /**
     * M6: 포맷 삭제 (소유권 검증)
     */
    public void deleteFormatWithOwnerCheck(String id, String userId) {
        Format format = getFormatWithOwnerCheck(id, userId);

        List<String> referencingSchedules = scheduleRepository.findAllByFormatId(id)
            .stream()
            .map(s -> s.getId())
            .toList();

        if (!referencingSchedules.isEmpty()) {
            throw new ConflictException("format is referenced by schedules", referencingSchedules);
        }

        formatRepository.delete(format);
        renderScanTrigger.requestScan(userId);
    }

    /**
     * Temporary DTO for import/export with assets.
     */
    public record FormatDocumentWithAssets(
        FormatDocument document,
        Map<String, String> assets // assetId -> dataURI
    ) {}
}
