package com.homecare.review.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.review.entity.ReviewEntity;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {
    Optional<ReviewEntity> findBySourceId(UUID sourceId);

    Page<ReviewEntity> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            String targetType, UUID targetId, String status, Pageable pageable);

    @Query("""
        select coalesce(avg(r.rating), 0.0), count(r)
        from ReviewEntity r
        where r.targetType = 'SERVICE_BOOKING'
          and r.targetId = :providerId
          and r.status = 'PUBLISHED'
        """)
    List<Object[]> summarizePublishedServiceReviews(@Param("providerId") UUID providerId);

    @Query("""
        select distinct r.targetId
        from ReviewEntity r
        where r.targetType = 'SERVICE_BOOKING'
        """)
    List<UUID> findDistinctServiceProviderIds();

    @Query("""
        select r from ReviewEntity r
        where r.targetType = 'SERVICE_BOOKING'
          and (:status is null or r.status = :status)
          and (:providerId is null or r.targetId = :providerId)
          and (:rating is null or r.rating = :rating)
          and (:q is null
               or lower(r.comment) like lower(concat('%', cast(:q as string), '%'))
               or lower(coalesce(r.authorName, '')) like lower(concat('%', cast(:q as string), '%'))
               or lower(r.authorProfileId) like lower(concat('%', cast(:q as string), '%'))
               or lower(cast(r.sourceId as string)) like lower(concat('%', cast(:q as string), '%')))
        order by r.createdAt desc, r.id asc
        """)
    Page<ReviewEntity> findAdminServiceReviews(
            @Param("status") String status,
            @Param("providerId") UUID providerId,
            @Param("rating") Integer rating,
            @Param("q") String q,
            Pageable pageable);
}
