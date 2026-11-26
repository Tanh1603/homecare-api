package com.homecare.booking.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.booking.entity.QuotationEntity;

public interface QuotationRepository extends JpaRepository<QuotationEntity, UUID> {

    List<QuotationEntity> findByServiceRequestIdOrderByVersionDesc(UUID serviceRequestId);

    List<QuotationEntity> findByServiceRequestIdAndQuoteKindOrderByVersionDesc(UUID serviceRequestId, String quoteKind);

    Optional<QuotationEntity> findFirstByServiceRequestIdOrderByVersionDesc(UUID serviceRequestId);

    Optional<QuotationEntity> findFirstByServiceRequestIdAndQuoteKindOrderByVersionDesc(UUID serviceRequestId, String quoteKind);

    Optional<QuotationEntity> findByIdAndServiceRequestId(UUID id, UUID serviceRequestId);

    List<QuotationEntity> findByBookingIdAndQuoteKindOrderByVersionDesc(UUID bookingId, String quoteKind);

    Optional<QuotationEntity> findFirstByBookingIdAndQuoteKindOrderByVersionDesc(UUID bookingId, String quoteKind);

    Optional<QuotationEntity> findByIdAndBookingId(UUID id, UUID bookingId);

    boolean existsByBookingIdAndQuoteKindAndStatus(UUID bookingId, String quoteKind, String status);

    @Query(value = """
            SELECT * FROM quotations
            WHERE status = 'SENT'
              AND valid_until < :now
            ORDER BY valid_until ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<QuotationEntity> lockExpiredSentQuotations(@Param("now") Instant now, @Param("limit") int limit);
}
