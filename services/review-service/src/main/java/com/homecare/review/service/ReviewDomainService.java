package com.homecare.review.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.error.DomainException;
import com.homecare.review.client.EvidenceVerificationClient;
import com.homecare.review.client.EvidenceVerificationClient.BookingEligibility;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.ReviewResponse;
import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;
import com.homecare.review.entity.ReviewEntity;
import com.homecare.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewDomainService {

    private final ReviewRepository reviewRepository;
    private final EvidenceVerificationClient verificationClient;

    @Transactional
    public ReviewResponse createServiceReview(String authorProfileId, CreateServiceReviewRequest request) {
        if (reviewRepository.findBySourceId(request.bookingId()).isPresent()) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "REVIEW_ALREADY_EXISTS",
                    "A review for this booking already exists.");
        }
        BookingEligibility eligibility = verificationClient.verifyBookingEligible(
                request.bookingId(), authorProfileId);
        if (!eligibility.eligible()) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "REVIEW_NOT_ELIGIBLE",
                    "Booking must be COMPLETED by the requester before submitting a review.");
        }
        UUID providerId = request.providerId() != null
                ? request.providerId()
                : (eligibility.providerId() != null
                        ? eligibility.providerId()
                        : UUID.fromString("11111111-1111-1111-1111-111111111111"));
        Instant now = Instant.now();
        ReviewEntity saved = reviewRepository.save(ReviewEntity.builder()
                .id(UUID.randomUUID())
                .authorProfileId(authorProfileId)
                .targetType("SERVICE_BOOKING")
                .sourceId(request.bookingId())
                .targetId(providerId)
                .rating(request.rating())
                .comment(request.comment().trim())
                .status("PUBLISHED")
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toResponse(saved);
    }

    @Transactional
    public ReviewResponse createProductReview(
            String authorProfileId,
            UUID productId,
            CreateProductReviewRequest request) {
        if (reviewRepository.findBySourceId(request.orderLineId()).isPresent()) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "REVIEW_ALREADY_EXISTS",
                    "A review for this order line already exists.");
        }
        if (!verificationClient.verifyOrderLineEligible(request.orderLineId(), authorProfileId)) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "REVIEW_NOT_ELIGIBLE",
                    "Order line must be DELIVERED to the requester before submitting a review.");
        }
        Instant now = Instant.now();
        ReviewEntity saved = reviewRepository.save(ReviewEntity.builder()
                .id(UUID.randomUUID())
                .authorProfileId(authorProfileId)
                .targetType("PRODUCT_ORDER_LINE")
                .sourceId(request.orderLineId())
                .targetId(productId)
                .rating(request.rating())
                .comment(request.comment().trim())
                .status("PUBLISHED")
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toResponse(saved);
    }

    @Transactional
    public ReviewResponse updateServiceReview(
            String authorProfileId,
            UUID reviewId,
            UpdateReviewRequest request) {
        ReviewEntity r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review not found."));
        if (!r.getAuthorProfileId().equals(authorProfileId)) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "Not the author of this review.");
        }
        if (request.rating() != null) {
            r.setRating(request.rating());
        }
        if (request.comment() != null && !request.comment().isBlank()) {
            r.setComment(request.comment().trim());
        }
        r.setUpdatedAt(Instant.now());
        return toResponse(reviewRepository.save(r));
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listProviderReviews(UUID providerId) {
        return reviewRepository.findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
                        "SERVICE_BOOKING", providerId, "PUBLISHED")
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listProductReviews(UUID productId) {
        return reviewRepository.findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
                        "PRODUCT_ORDER_LINE", productId, "PUBLISHED")
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ReviewResponse toResponse(ReviewEntity r) {
        return new ReviewResponse(
                r.getId(),
                r.getAuthorProfileId(),
                r.getTargetType(),
                r.getSourceId(),
                r.getTargetId(),
                r.getRating(),
                r.getComment(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
