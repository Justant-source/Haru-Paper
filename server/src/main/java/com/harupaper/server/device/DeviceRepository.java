package com.harupaper.server.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByTokenHash(String tokenHash);

    Optional<Device> findByOwnerUserId(String ownerUserId);
}
