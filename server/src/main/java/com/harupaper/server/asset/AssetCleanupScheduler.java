package com.harupaper.server.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatDocument;
import com.harupaper.server.format.FormatDocumentSupport;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.widget.WidgetInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scheduled task to clean up unreferenced assets that are older than 24 hours.
 * Runs daily at 4:00 AM KST.
 */
@Slf4j
@Component
public class AssetCleanupScheduler {

    private static final long ASSET_RETENTION_HOURS = 24;

    private final AssetRepository assetRepository;
    private final FormatRepository formatRepository;
    private final ObjectMapper objectMapper;
    private final String filesDir;

    public AssetCleanupScheduler(AssetRepository assetRepository,
                                FormatRepository formatRepository,
                                ObjectMapper objectMapper,
                                @Value("${haru.files-dir}") String filesDir) {
        this.assetRepository = assetRepository;
        this.formatRepository = formatRepository;
        this.objectMapper = objectMapper;
        this.filesDir = filesDir;
    }

    /**
     * Run cleanup daily at 4:00 AM (0 0 4 * * *)
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupUnreferencedAssets() {
        log.info("Starting asset cleanup task");

        try {
            // Collect all referenced assetIds from formats
            Set<String> referencedAssets = collectReferencedAssets();

            // Find all assets
            List<Asset> allAssets = assetRepository.findAll();

            // Current time for age check
            Instant cutoff = Instant.now().minus(ASSET_RETENTION_HOURS, ChronoUnit.HOURS);

            int deleted = 0;
            for (Asset asset : allAssets) {
                // Skip if referenced or not old enough
                if (referencedAssets.contains(asset.getId())) {
                    continue;
                }
                if (asset.getCreatedAt().isAfter(cutoff)) {
                    continue;
                }

                // Delete file first
                try {
                    Path filePath = Paths.get(filesDir, asset.getPath());
                    if (Files.exists(filePath)) {
                        Files.delete(filePath);
                        log.debug("Deleted asset file: {}", asset.getPath());
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete asset file: {}", asset.getPath(), e);
                }

                // Delete DB entry
                try {
                    assetRepository.delete(asset);
                    log.debug("Deleted asset record: {}", asset.getId());
                    deleted++;
                } catch (Exception e) {
                    log.warn("Failed to delete asset record: {}", asset.getId(), e);
                }
            }

            log.info("Asset cleanup completed: {} assets deleted", deleted);
        } catch (Exception e) {
            log.error("Asset cleanup task failed", e);
        }
    }

    /**
     * Collect all assetIds referenced by formats.
     */
    private Set<String> collectReferencedAssets() {
        Set<String> referencedAssets = new HashSet<>();

        List<Format> allFormats = formatRepository.findAll();
        for (Format format : allFormats) {
            try {
                // v1·v2 포맷은 자동으로 up-convert되어 widgets를 반환한다
                FormatDocument doc = FormatDocumentSupport.readDocument(format.getBody(), objectMapper);
                if (doc.widgets() != null) {
                    for (WidgetInstance widget : doc.widgets()) {
                        if ("image".equals(widget.type()) && widget.props() != null) {
                            Object assetIdObj = widget.props().get("assetId");
                            if (assetIdObj instanceof String assetId) {
                                referencedAssets.add(assetId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse format {}: {}", format.getId(), e.getMessage());
            }
        }

        return referencedAssets;
    }
}
