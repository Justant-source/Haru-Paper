package com.harupaper.server.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.render.RenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Format REST endpoints (app-facing, no authentication).
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
     * GET /api/formats - List all formats
     */
    @GetMapping
    public ResponseEntity<List<FormatSummary>> listFormats() {
        List<Format> formats = formatService.listFormats();
        List<FormatSummary> summaries = formats.stream()
            .map(this::toSummary)
            .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * POST /api/formats - Create new format
     */
    @PostMapping
    public ResponseEntity<FormatDetailResponse> createFormat(@RequestBody Map<String, Object> rawBody) {
        FormatDocument document = formatService.getFormatValidator()
                .validateAndParse(rawBody, false, objectMapper);
        Format created = formatService.createFormat(document);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetailResponse(created));
    }

    /**
     * GET /api/formats/{id} - Get format by id
     */
    @GetMapping("/{id}")
    public ResponseEntity<FormatDetailResponse> getFormat(@PathVariable String id) {
        Format format = formatService.getFormat(id);
        return ResponseEntity.ok(toDetailResponse(format));
    }

    /**
     * PUT /api/formats/{id} - Update format
     */
    @PutMapping("/{id}")
    public ResponseEntity<FormatDetailResponse> updateFormat(
        @PathVariable String id,
        @RequestBody Map<String, Object> rawBody) {
        FormatDocument document = formatService.getFormatValidator()
                .validateAndParse(rawBody, false, objectMapper);
        Format updated = formatService.updateFormat(id, document);
        return ResponseEntity.ok(toDetailResponse(updated));
    }

    /**
     * DELETE /api/formats/{id} - Delete format
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFormat(@PathVariable String id) {
        formatService.deleteFormat(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/formats/import - Import format with embedded assets
     */
    @PostMapping("/import")
    public ResponseEntity<FormatDetailResponse> importFormat(@RequestBody Map<String, Object> importJson)
        throws IOException {
        // "assets"는 FormatDocument에 없는 필드라 먼저 떼어내야 한다. 남은 부분만 FormatDocument로
        // 변환한다(그 안에 정말 모르는 필드가 남아 있으면 Jackson이 예외를 던지고,
        // 그건 아래 doc 변환 실패 -> 422로 GlobalExceptionHandler가 처리한다).
        Map<String, Object> importJsonCopy = new java.util.HashMap<>(importJson);
        @SuppressWarnings("unchecked")
        Map<String, String> assets = (Map<String, String>) importJsonCopy.remove("assets");
        FormatDocument doc;
        try {
            doc = objectMapper.convertValue(importJsonCopy, FormatDocument.class);
        } catch (IllegalArgumentException e) {
            // format-schema.md 5절: 가져오기에서 알 수 없는 필드는 422(400 아님)
            String path = (e.getCause() instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException upe)
                    ? String.join(".", upe.getPath().stream().map(r -> r.getFieldName()).toList())
                    : "document";
            throw new com.harupaper.server.common.exception.ValidationException(
                    "format document is invalid",
                    List.of(new com.harupaper.server.common.exception.ValidationException.FieldError(
                            path, e.getMessage())));
        }

        FormatService.FormatDocumentWithAssets importData = new FormatService.FormatDocumentWithAssets(doc, assets != null ? assets : Map.of());
        Format imported = formatService.importFormat(importData);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetailResponse(imported));
    }

    /**
     * GET /api/formats/{id}/export - Export format with embedded assets
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<Map<String, Object>> exportFormat(@PathVariable String id) throws IOException {
        Format format = formatService.getFormat(id);
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
     * GET /api/formats/{id}/preview.png - Render saved format as preview
     */
    @GetMapping(value = "/{id}/preview.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewSavedFormat(
        @PathVariable String id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
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
