package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.QuotationItemEntity;

public interface QuotationItemRepository extends JpaRepository<QuotationItemEntity, UUID> {

    List<QuotationItemEntity> findByQuotationIdOrderByRowIndexAsc(UUID quotationId);
}
