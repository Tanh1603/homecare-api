package com.homecare.household.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.household.entity.UserAddress;

import jakarta.persistence.LockModeType;

public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    @Query("""
            SELECT a FROM UserAddress a
            JOIN FETCH a.location w
            LEFT JOIN FETCH w.parent
            WHERE a.ownerProfileId = :ownerProfileId
              AND a.active = true
            ORDER BY a.defaultShipping DESC, a.updatedAt DESC
            """)
    List<UserAddress> findActiveWithLocationByOwner(@Param("ownerProfileId") String ownerProfileId);

    Optional<UserAddress> findByIdAndOwnerProfileIdAndActiveTrue(UUID id, String ownerProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM UserAddress a WHERE a.ownerProfileId = :ownerProfileId ORDER BY a.id ASC")
    List<UserAddress> lockAllByOwnerOrderById(@Param("ownerProfileId") String ownerProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM UserAddress a WHERE a.id = :id AND a.ownerProfileId = :ownerProfileId")
    Optional<UserAddress> lockByIdAndOwner(@Param("id") UUID id, @Param("ownerProfileId") String ownerProfileId);
}
