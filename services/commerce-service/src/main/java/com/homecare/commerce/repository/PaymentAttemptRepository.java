package com.homecare.commerce.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.PaymentAttemptEntity;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {
    Optional<PaymentAttemptEntity> findByTxnRef(String txnRef);
    List<PaymentAttemptEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
