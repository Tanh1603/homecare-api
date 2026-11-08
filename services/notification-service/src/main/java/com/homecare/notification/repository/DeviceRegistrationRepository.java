package com.homecare.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.notification.entity.DeviceRegistrationEntity;

public interface DeviceRegistrationRepository extends JpaRepository<DeviceRegistrationEntity, UUID> {
    Optional<DeviceRegistrationEntity> findByPushToken(String pushToken);

    List<DeviceRegistrationEntity> findByRecipientIdAndActiveTrue(String recipientId);
}
