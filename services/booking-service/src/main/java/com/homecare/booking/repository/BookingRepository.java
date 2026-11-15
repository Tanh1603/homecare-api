package com.homecare.booking.repository;

import com.homecare.booking.entity.BookingEntity;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {
    @Query("""
        select b from BookingEntity b where (:requester is null or b.requesterProfileId = :requester)
        and (:technician is null or b.assignedTechnicianProfileId = :technician)
        and (:provider is null or b.providerId = :provider)
        and (:status is null or b.status = :status)
        """)
    Page<BookingEntity> search(String requester, String technician, UUID provider, String status, Pageable pageable);

    @Lock(PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id = :id")
    Optional<BookingEntity> lockById(UUID id);
    List<BookingEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId);
    List<BookingEntity> findAllByOrderByCreatedAtDesc();

    Page<BookingEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId, Pageable pageable);
    Page<BookingEntity> findByAssignedTechnicianProfileIdOrderByCreatedAtDesc(String assignedTechnicianProfileId, Pageable pageable);
    Page<BookingEntity> findByProviderIdOrderByCreatedAtDesc(UUID providerId, Pageable pageable);
    Page<BookingEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
