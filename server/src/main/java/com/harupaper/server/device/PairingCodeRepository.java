package com.harupaper.server.device;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PairingCodeRepository extends JpaRepository<PairingCode, String> {
}
