package com.homecare.provider.repository;

import com.homecare.provider.entity.Technician;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    @Query("""
        select t from Technician t where t.status = 'ACTIVE'
        and exists (select 1 from ProviderEntity p where p.id = t.providerId and p.status = 'ACTIVE')
        and (:providerId is null or t.providerId = :providerId)
        and (:q is null or lower(t.fullName) like lower(concat('%', cast(:q as string), '%')))
        """)
    Page<Technician> search(UUID providerId, String q, Pageable pageable);

    @Query("""
        select t from Technician t where (:providerId is null or t.providerId = :providerId)
        and (:q is null or lower(t.fullName) like lower(concat('%', cast(:q as string), '%')))
        """)
    Page<Technician> searchIncludingInactive(UUID providerId, String q, Pageable pageable);
    Optional<Technician> findByProfileId(String profileId);

    @Lock(PESSIMISTIC_WRITE)
    @Query("select t from Technician t where t.id = :id")
    Optional<Technician> lockById(UUID id);

    @Lock(PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select t from Technician t where t.id = :id")
    Optional<Technician> lockByIdNoWait(UUID id);
    List<Technician> findByProviderIdAndStatus(UUID providerId, String status);
    List<Technician> findByStatus(String status);
}
