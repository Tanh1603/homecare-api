package com.homecare.commerce.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.OrderReturnRequestEntity;

public interface OrderReturnRequestRepository extends JpaRepository<OrderReturnRequestEntity, UUID> {
    List<OrderReturnRequestEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
