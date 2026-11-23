package com.homecare.commerce.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.homecare.commerce.entity.OrderComponentSnapshotEntity;

public interface OrderComponentSnapshotRepository extends JpaRepository<OrderComponentSnapshotEntity, UUID> {
    List<OrderComponentSnapshotEntity> findByOrderId(UUID orderId);
    Optional<OrderComponentSnapshotEntity> findByOrderLineIdAndComponentType(UUID orderLineId, String componentType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from OrderComponentSnapshotEntity s
            where s.orderLineId = :orderLineId and s.componentType = :componentType
            """)
    Optional<OrderComponentSnapshotEntity> lockByOrderLineIdAndComponentType(
            @Param("orderLineId") UUID orderLineId,
            @Param("componentType") String componentType);
}
