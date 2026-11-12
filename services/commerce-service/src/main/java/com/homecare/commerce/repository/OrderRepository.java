package com.homecare.commerce.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.OrderEntity;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    List<OrderEntity> findByBuyerProfileIdOrderByCreatedAtDesc(String buyerProfileId);
}
