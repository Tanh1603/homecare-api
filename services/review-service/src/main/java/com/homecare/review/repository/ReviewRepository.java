package com.homecare.review.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.review.entity.ReviewEntity;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {
    Optional<ReviewEntity> findBySourceId(UUID sourceId);
    List<ReviewEntity> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            String targetType, UUID targetId, String status);
}
