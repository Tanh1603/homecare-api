package com.homecare.review.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.review.entity.ReviewEntity;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {
    Optional<ReviewEntity> findBySourceId(UUID sourceId);
    Page<ReviewEntity> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            String targetType, UUID targetId, String status, Pageable pageable);
}
