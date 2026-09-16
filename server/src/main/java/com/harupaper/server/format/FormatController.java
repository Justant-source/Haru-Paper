package com.harupaper.server.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.render.RenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M6: Format REST endpoints (사용자 인증 필수).
 * 소유권 스코핑: 목록은 현재 사용자만, 단건 조회·수정·삭제는 소유자 아니면 404
 */
@Slf4j
@RestController
@RequestMapping("/api/formats")
public class FormatController {

    private final FormatService formatService;
    private final RenderService renderService;
    private final ObjectMapper objectMapper;

    public FormatController(FormatService formatService, RenderService renderService, ObjectMapper objectMapper) {
        this.formatService = formatService;
        this.renderService = renderService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/formats - List formats owned by current user
     */
    @GetMapping
    public ResponseEntity<List<FormatSummary>> listFormats(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        List<Format> formats = formatService.listFormatsByOwner(userId);
        List<FormatSummary> summaries = formats.stream()
            .map(this::toSummary)
            .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * POST /api/formats - Create new format
     */
    @PostMapping
    public ResponseEntity<FormatDetailResponse> createFormat(
            @RequestBody Map<String, Object> rawBody,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        FormatDocument document = formatService.getFormatValidator()
                .validateAndParse(rawBody, false, objectMapper);
        Format created = formatService.createFormatWithOwner(document, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetailResponse(created));
    }

    /**
     * GET /api/formats/{id} - Get format by id (owner only or 404)
     */
    @GetMapping("/{id}")
    public ResponseEntity<FormatDetailResponse> getFormat(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        Format format = formatService.getFormatWithOwnerCheck(id, userId);
        return ResponseEntity.ok(toDetailResponse(format));
    }

    /**
     * PUT /api/formats/{id} - Update format (owner only or 404)
     */
    @PutMapping("/{id}")
    public ResponseEntity<FormatDetailResponse> updateFormat(
        @PathVariable String id,
        @RequestBody Map<String, Object> rawBody,
        @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        FormatDocument document = formatService.getFormatValidator()
                .validateAndParse(rawBody, false, objectMapper);
        Format updated = formatService.updateFormatWithOwnerCheck(id, document, userId);
        return ResponseEntity.ok(toDetailResponse(updated));
    }

    /**
     * DELETE /api/formats/{id} - Delete format (owner only or 404)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFormat(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        formatService.deleteFormatWithOwnerCheck(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/formats/import - Import format with embedded assets
     */
    @PostMapping("/import")
    public ResponseEntity<FormatDetailResponse> importFormat(
        @RequestBody Map<String, Object> importJson,
        @AuthenticationPrincipal UserPrincipal principal)
        throws IOException {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        FormatDocument doc = formatService.getFormatValidator()
                .validateAndParse(importJson, true, objectMapper);
        Object assetsObj = importJson.get("assets");
        Map<String, String> assets = Map.of();
        if (assetsObj instanceof Map<?, ?> rawAssets) {
            Map<String, String> copied = new HashMap<>();
            for (Map.Entry<?, ?> entry : rawAssets.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                    throw new com.harupaper.server.common.exception.ValidationException(
                            "format document is invalid",
                            List.of(new com.harupaper.server.common.exception.ValidationException.FieldError(
                                    "assets", "asset entries must be string data URIs")));
                }
                copied.put(key, value);
            }
            assets = copied;
        } else if (assetsObj != null) {
            throw new com.harupaper.server.common.exception.ValidationException(
                    "format document is invalid",
                    List.of(new com.harupaper.server.common.exception.ValidationException.FieldError(
                            "assets", "assets must be an object of data URIs")));
        }

        FormatService.FormatDocumentWithAssets importData = new FormatService.FormatDocumentWithAssets(doc, assets);
        Format imported = formatService.importFormat(importData, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetailResponse(imported));
    }

    /**
     * GET /api/formats/{id}/export - Export format with embedded assets (owner only or 404)
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<Map<String, Object>> exportFormat(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        Format format = formatService.getFormatWithOwnerCheck(id, userId);
        FormatService.FormatDocumentWithAssets exported = formatService.exportFormat(id);

        // Build response JSON with embedded assets
        FormatDocument doc = exported.document();
        Map<String, Object> response = objectMapper.convertValue(doc, Map.class);
        response.put("assets", exported.assets());

        // Set Content-Disposition header
        String filename = format.getName() + ".haru-format.json";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
            .attachment()
            .filename(filename)
            .build());

        return ResponseEntity.ok()
            .headers(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .body(response);
    }

    /**
     * GET /api/formats/{id}/preview.png - Render saved format as preview (owner only or 404)
     */
    @GetMapping(value = "/{id}/preview.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewSavedFormat(
        @PathVariable String id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        // Check ownership
        formatService.getFormatWithOwnerCheck(id, userId);

        if (date == null) {
            date = TimeUtils.todayInKST();
        }

        var renderResult = renderService.renderSavedFormatPreview(id, date);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return ResponseEntity.ok()
            .headers(headers)
            .body(renderResult.pngBytes());
    }

    /**
     * POST /api/formats/preview - Render ephemeral (unsaved) format
     */
    @PostMapping(value = "/preview", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewEphemeralFormat(
        @RequestBody Map<String, Object> rawBody,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = TimeUtils.todayInKST();
        }

        // 검증만 하고 저장하지 않는다 (assets 필드도 여기선 허용하지 않는다 — 저장 안 된 편집본 미리보기)
        FormatDocument document = formatService.getFormatValidator()
                .validateAndParse(rawBody, false, objectMapper);

        byte[] pngBytes = renderService.renderEphemeral(document, date);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return ResponseEntity.ok()
            .headers(headers)
            .body(pngBytes);
    }

    // Helper methods

    private FormatSummary toSummary(Format format) {
        FormatDocument doc = formatService.getFormatDocument(format);
        return new FormatSummary(
            format.getId(),
            format.getName(),
            doc.meta().author() != null ? doc.meta().author() : "",
            doc.meta().forkedFrom(),
            format.getHasDynamicBlocks(),
            TimeUtils.toIso8601(format.getUpdatedAt())
        );
    }

    private FormatDetailResponse toDetailResponse(Format format) {
        FormatDocument doc = formatService.getFormatDocument(format);
        return new FormatDetailResponse(
            format.getId(),
            doc,
            format.getHasDynamicBlocks(),
            TimeUtils.toIso8601(format.getCreatedAt()),
            TimeUtils.toIso8601(format.getUpdatedAt())
        );
    }

    // DTOs for responses

    public record FormatSummary(
        String id,
        String name,
        String author,
        ForkedFrom forkedFrom,
        Boolean hasDynamicBlocks,
        String updatedAt
    ) {}

    public record FormatDetailResponse(
        String id,
        FormatDocument document,
        Boolean hasDynamicBlocks,
        String createdAt,
        String updatedAt
    ) {}
}
