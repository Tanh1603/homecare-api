package com.homecare.review.service;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.PageResponse;
import com.homecare.review.client.EvidenceVerificationClient;
import com.homecare.review.client.EvidenceVerificationClient.BookingEligibility;
import com.homecare.review.client.EvidenceVerificationClient.OrderLineEligibility;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.ReviewResponse;
import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;
import com.homecare.review.entity.ReviewEntity;
import com.homecare.review.exception.ReviewErrorCode;
import com.homecare.review.repository.ReviewRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ReviewDomainService {

    private final ReviewRepository reviewRepository;
    private final EvidenceVerificationClient verificationClient;
    private final PlatformTransactionManager transactionManager;

    public ReviewResponse createServiceReview(String authorProfileId, CreateServiceReviewRequest request) {
        return createServiceReview(authorProfileId, null, request);
    }

    public ReviewResponse createServiceReview(
            String authorProfileId, String authorName, CreateServiceReviewRequest request) {
        var preExisting = reviewRepository.findBySourceId(request.bookingId());
        if (preExisting.isPresent() && !"DELETED".equals(preExisting.get().getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }
        BookingEligibility eligibility = verificationClient.verifyBookingEligible(
                request.bookingId(), authorProfileId);
        if (!eligibility.eligible() || eligibility.providerId() == null) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_ELIGIBLE);
        }
        if (request.providerId() != null && !request.providerId().equals(eligibility.providerId())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "providerId does not match booking provider.");
        }
        UUID providerId = eligibility.providerId();
        return saveOrReuseReviewInTx(
                request.bookingId(),
                "SERVICE_BOOKING",
                providerId,
                authorProfileId,
                authorName,
                request.rating(),
                request.comment());
    }

    public ReviewResponse createProductReview(
            String authorProfileId,
            UUID productId,
            CreateProductReviewRequest request) {
        return createProductReview(authorProfileId, null, productId, request);
    }

    public ReviewResponse createProductReview(
            String authorProfileId,
            String authorName,
            UUID productId,
            CreateProductReviewRequest request) {
        var preExisting = reviewRepository.findBySourceId(request.orderLineId());
        if (preExisting.isPresent() && !"DELETED".equals(preExisting.get().getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }
        OrderLineEligibility eligibility = verificationClient.verifyOrderLineEligibility(
                request.orderLineId(), authorProfileId);
        if (!eligibility.eligible()) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_ELIGIBLE);
        }
        if (eligibility.productId() == null || !productId.equals(eligibility.productId())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "productId does not match order line product.");
        }
        return saveOrReuseReviewInTx(
                request.orderLineId(),
                "PRODUCT_ORDER_LINE",
                productId,
                authorProfileId,
                authorName,
                request.rating(),
                request.comment());
    }

    private ReviewResponse saveOrReuseReviewInTx(
            UUID sourceId,
            String targetType,
            UUID targetId,
            String authorProfileId,
            String authorName,
            int rating,
            String comment) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            return tx.execute(status -> {
                var existing = reviewRepository.findBySourceId(sourceId);
                if (existing.isPresent() && !"DELETED".equals(existing.get().getStatus())) {
                    throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
                }
                Instant now = Instant.now();
                ReviewEntity review = existing.orElseGet(() -> ReviewEntity.builder()
                        .id(UUID.randomUUID())
                        .targetType(targetType)
                        .sourceId(sourceId)
                        .createdAt(now)
                        .build());
                review.setAuthorProfileId(authorProfileId);
                review.setAuthorName(authorName);
                review.setTargetId(targetId);
                review.setRating(rating);
                review.setComment(comment.trim());
                review.setStatus(EntityStatus.PUBLISHED.name());
                review.setUpdatedAt(now);
                return toResponse(reviewRepository.saveAndFlush(review));
            });
        } catch (DataIntegrityViolationException ex) {
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }
    }

    private ReviewEntity requireServiceReview(String authorProfileId, UUID reviewId) {
        ReviewEntity r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!"SERVICE_BOOKING".equalsIgnoreCase(r.getTargetType())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Cannot update non-service review via service review endpoint.");
        }
        if (!"PUBLISHED".equals(r.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND);
        }
        if (!r.getAuthorProfileId().equals(authorProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return r;
    }

    @Transactional
    public ReviewResponse updateServiceReview(
            String authorProfileId,
            UUID reviewId,
            UpdateReviewRequest request) {
        ReviewEntity r = requireServiceReview(authorProfileId, reviewId);
        if (request.rating() != null) {
            r.setRating(request.rating());
        }
        if (request.comment() != null && !request.comment().isBlank()) {
            r.setComment(request.comment().trim());
        }
        r.setUpdatedAt(Instant.now());
        return toResponse(reviewRepository.save(r));
    }

    @Transactional
    public void deleteServiceReview(String authorProfileId, UUID reviewId) {
        ReviewEntity r = requireServiceReview(authorProfileId, reviewId);
        r.setStatus("DELETED");
        r.setUpdatedAt(Instant.now());
        reviewRepository.save(r);
    }

    @Transactional(readOnly = true)
    public ReviewResponse getServiceReviewByBooking(String authorProfileId, UUID bookingId) {
        return reviewRepository.findBySourceId(bookingId)
                .filter(r -> "SERVICE_BOOKING".equalsIgnoreCase(r.getTargetType())
                        && "PUBLISHED".equals(r.getStatus())
                        && authorProfileId.equals(r.getAuthorProfileId()))
                .map(this::toResponse)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> providerReviewsPage(UUID providerId, int page, int size) {
        return reviewsPage("SERVICE_BOOKING", providerId, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> productReviewsPage(UUID productId, int page, int size) {
        return reviewsPage("PRODUCT_ORDER_LINE", productId, page, size);
    }

    private PageResponse<ReviewResponse> reviewsPage(String targetType, UUID targetId, int page, int size) {
        PageResponse.validate(page, size);
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        var result = reviewRepository.findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
                targetType, targetId, EntityStatus.PUBLISHED.name(), pageable);
        return PageResponse.of(result.map(this::toResponse).getContent(), page, size, result.getTotalElements());
    }

    private ReviewResponse toResponse(ReviewEntity r) {
        return new ReviewResponse(
                r.getId(),
                r.getAuthorProfileId(),
                r.getAuthorName(),
                r.getTargetType(),
                r.getSourceId(),
                r.getTargetId(),
                r.getRating(),
                r.getComment(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private ReviewEntity requireProductReview(String user, UUID productId, UUID reviewId) {
        var review = reviewRepository.findById(reviewId).orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!"PRODUCT_ORDER_LINE".equals(review.getTargetType()) || !productId.equals(review.getTargetId()) || !"PUBLISHED".equals(review.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND);
        }
        if (!user.equals(review.getAuthorProfileId())) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        return review;
    }

    @Transactional
    public ReviewResponse updateProductReview(String user, UUID productId, UUID reviewId, UpdateReviewRequest input) {
        var review = requireProductReview(user, productId, reviewId);
        if (input.rating() != null) review.setRating(input.rating());
        if (input.comment() != null && !input.comment().isBlank()) review.setComment(input.comment().trim());
        review.setUpdatedAt(Instant.now());
        return toResponse(reviewRepository.save(review));
    }

    @Transactional
    public void deleteProductReview(String user, UUID productId, UUID reviewId) {
        var review = requireProductReview(user, productId, reviewId);
        review.setStatus("DELETED"); review.setUpdatedAt(Instant.now()); reviewRepository.save(review);
    }
}
