package com.homecare.commerce.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.OrderLineEntity;

public interface OrderLineRepository extends JpaRepository<OrderLineEntity, UUID> {
    List<OrderLineEntity> findByOrderId(UUID orderId);
}
