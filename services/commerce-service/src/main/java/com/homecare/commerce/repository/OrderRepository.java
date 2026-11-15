package com.homecare.commerce.repository;

import static jakarta.persistence.LockModeType.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.homecare.commerce.entity.OrderEntity;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    @Query("""
            select o from OrderEntity o where (:buyer is null or o.buyerProfileId = :buyer)
            and (:merchant is null or o.merchantId = :merchant)
            and (:status is null or o.status = :status)
            and (:q is null or lower(o.shippingAddress) like lower(concat('%', :q, '%')))
            """)
    Page<OrderEntity> search(String buyer, UUID merchant, String status, String q, Pageable pageable);

    @Lock(PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> lockById(UUID id);

    Page<OrderEntity> findByStatusAndQuoteExpiresAtBefore(String status, Instant now, Pageable pageable);

    List<OrderEntity> findByBuyerProfileIdOrderByCreatedAtDesc(String buyerProfileId);

    List<OrderEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    List<OrderEntity> findAllByOrderByCreatedAtDesc();
}
