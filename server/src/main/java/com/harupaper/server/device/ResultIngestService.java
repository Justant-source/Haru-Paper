package com.harupaper.server.device;

import com.harupaper.server.command.Command;
import com.harupaper.server.command.CommandRepository;
import com.harupaper.server.common.time.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 결과 한 건을 별도 트랜잭션으로 insert한다. 같은 트랜잭션에서 PK 충돌을 잡으면
 * 세션이 rollback-only가 되어 배치 전체가 실패한다(docs/server/api.md 멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultIngestService {

    private final ResultRepository resultRepository;
    private final CommandRepository commandRepository;
    private final DeviceRepository deviceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ingestNew(DeviceDto.ResultDto resultDto) {
        if (resultRepository.existsById(resultDto.resultId())) {
            return false;
        }

        Instant now = Instant.now();
        Result result = Result.builder()
                .id(resultDto.resultId())
                .occurrenceKey(blankToNull(resultDto.occurrenceKey()))
                .commandId(blankToNull(resultDto.commandId()))
                .formatId(resultDto.formatId())
                .renderId(resultDto.renderId())
                .status(resultDto.status())
                .detail(resultDto.detail())
                .scheduledAt(TimeUtils.parseIso8601(resultDto.scheduledAt()))
                .executedAt(TimeUtils.parseIso8601(resultDto.executedAt()))
                .receivedAt(now)
                .build();

        try {
            resultRepository.saveAndFlush(result);
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate resultId on insert: {}", resultDto.resultId());
            return false;
        }

        if (result.getCommandId() != null) {
            Optional<Command> cmdOpt = commandRepository.findById(result.getCommandId());
            if (cmdOpt.isPresent()) {
                Command cmd = cmdOpt.get();
                cmd.setStatus("done");
                cmd.setCompletedAt(now);
                commandRepository.save(cmd);
            }
        }

        Device device = deviceRepository.findById(1).orElse(null);
        if (device != null && "manual_flag".equals(device.getPaperPolicy())) {
            if ("failed".equals(resultDto.status()) || "skipped_printer_offline".equals(resultDto.status())) {
                device.setPaperStateManual(false);
                device.setPaperStateUpdatedAt(now);
                device.setPaperStateUpdatedBy("server");
                deviceRepository.save(device);
            }
        }
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
