package com.homecare.booking.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.QuotationEntity;

public interface QuotationRepository extends JpaRepository<QuotationEntity, UUID> {

    List<QuotationEntity> findByServiceRequestIdOrderByVersionDesc(UUID serviceRequestId);

    Optional<QuotationEntity> findFirstByServiceRequestIdOrderByVersionDesc(UUID serviceRequestId);

    Optional<QuotationEntity> findByIdAndServiceRequestId(UUID id, UUID serviceRequestId);
}
