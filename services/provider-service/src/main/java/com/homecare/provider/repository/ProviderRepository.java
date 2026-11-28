package com.homecare.provider.repository;

import com.homecare.provider.entity.ProviderEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderRepository extends JpaRepository<ProviderEntity, UUID> {
    @Query("""
        select p from ProviderEntity p where p.status = 'ACTIVE'
        and (:city is null or lower(p.city) = lower(cast(:city as string)))
        and (:q is null or lower(p.name) like lower(concat('%', cast(:q as string), '%')))
        """)
    Page<ProviderEntity> search(String city, String q, Pageable pageable);

    Optional<ProviderEntity> findByOrganizationId(UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE providers
        SET rating_avg = :ratingAvg,
            review_count = :reviewCount,
            rating_calculated_at = :calculatedAt
        WHERE id = :providerId
          AND (rating_calculated_at IS NULL OR rating_calculated_at < :calculatedAt)
        """, nativeQuery = true)
    int updateRatingIfNewer(
            @Param("providerId") UUID providerId,
            @Param("ratingAvg") double ratingAvg,
            @Param("reviewCount") int reviewCount,
            @Param("calculatedAt") Instant calculatedAt);
}
