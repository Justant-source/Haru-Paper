package com.harupaper.server.admin;

import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.command.CommandRepository;
import com.harupaper.server.device.ResultRepository;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.render.RenderRepository;
import com.harupaper.server.schedule.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin service: 관리자 기능 구현
 *
 * - claimLegacyResources: owner_user_id=NULL인 리소스들을 관리자 소유로 변경
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final FormatRepository formatRepository;
    private final ScheduleRepository scheduleRepository;
    private final AssetRepository assetRepository;
    private final RenderRepository renderRepository;
    private final CommandRepository commandRepository;
    private final ResultRepository resultRepository;

    /**
     * 레거시 리소스(owner_user_id=NULL) 모두를 관리자 소유로 변경
     * JPQL 벌크 업데이트로 효율적으로 처리
     */
    @Transactional
    public AdminController.ClaimLegacyResponse claimLegacyResources(String adminUserId) {
        int formats = formatRepository.updateOwnerForNull(adminUserId);
        int schedules = scheduleRepository.updateOwnerForNull(adminUserId);
        int assets = assetRepository.updateOwnerForNull(adminUserId);
        int renders = renderRepository.updateOwnerForNull(adminUserId);
        int commands = commandRepository.updateOwnerForNull(adminUserId);
        int results = resultRepository.updateOwnerForNull(adminUserId);

        log.info("Claimed legacy resources for admin {}: formats={}, schedules={}, assets={}, renders={}, commands={}, results={}",
                adminUserId, formats, schedules, assets, renders, commands, results);

        return new AdminController.ClaimLegacyResponse(formats, schedules, assets, renders, commands, results);
    }
}
