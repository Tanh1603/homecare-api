package com.homecare.commerce.repository;

import com.homecare.commerce.entity.RefundOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundOperationRepository extends JpaRepository<RefundOperationEntity, UUID> {
    Optional<RefundOperationEntity> findByReturnRequestId(UUID returnRequestId);
}
