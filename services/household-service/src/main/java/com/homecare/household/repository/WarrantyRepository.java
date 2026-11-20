package com.homecare.household.repository;

import com.homecare.household.entity.Warranty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface WarrantyRepository extends JpaRepository<Warranty, UUID> {
    void deleteByDeviceId(UUID deviceId);
    @Query("select w from Warranty w where (:owner is null or w.ownerProfileId = :owner) and (:org is null or w.organizationId = :org)")
    Page<Warranty> search(String owner, UUID org, Pageable pageable);
    List<Warranty> findByDeviceIdOrderByEndsOnDesc(UUID deviceId);

    List<Warranty> findByOrganizationIdOrderByEndsOnDesc(UUID organizationId);

    List<Warranty> findAllByOrderByEndsOnDesc();

    Optional<Warranty> findByOrderLineId(UUID orderLineId);

    Optional<Warranty> findFirstByOrderLineIdOrderByCreatedAtAsc(UUID orderLineId);

    List<Warranty> findAllByOrderLineId(UUID orderLineId);

    @Lock(PESSIMISTIC_WRITE)
    @Query("select w from Warranty w where w.orderLineId = :orderLineId")
    List<Warranty> findAllByOrderLineIdForUpdate(UUID orderLineId);

    Optional<Warranty> findByUnitKey(String unitKey);
}
