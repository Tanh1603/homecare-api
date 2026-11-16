package com.homecare.commerce.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.commerce.entity.OrderReturnRequestEntity;

public interface OrderReturnRequestRepository extends JpaRepository<OrderReturnRequestEntity, UUID> {
    List<OrderReturnRequestEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update OrderReturnRequestEntity r set r.version = 0 where r.version is null")
    int backfillNullVersions();
}
