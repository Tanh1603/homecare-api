package com.homecare.commerce.repository;

import com.homecare.commerce.entity.InventoryEntity;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InventoryRepository extends JpaRepository<InventoryEntity, UUID> {
    Optional<InventoryEntity> findByProductId(UUID productId);
    @Lock(PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.productId = :productId")
    Optional<InventoryEntity> lockByProductId(UUID productId);
}
