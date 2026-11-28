package com.homecare.review.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.review.client.EvidenceVerificationClient;
import com.homecare.review.client.EvidenceVerificationClient.BookingEligibility;
import com.homecare.review.client.EvidenceVerificationClient.OrderLineEligibility;
import com.homecare.review.dto.ReviewDtos.AdminResyncRatingResponse;
import com.homecare.review.dto.ReviewDtos.AdminReviewResponse;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.ProviderRatingSnapshot;
import com.homecare.review.dto.ReviewDtos.ReviewResponse;
import com.homecare.review.dto.ReviewDtos.ServiceReviewEligibilityResponse;
import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;
import com.homecare.review.entity.ReviewEntity;
import com.homecare.review.exception.ReviewErrorCode;
import com.homecare.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewDomainService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int CREATE_WINDOW_DAYS = 30;
    public static final int EDIT_WINDOW_DAYS = 7;
    public static final int MAX_EDIT_COUNT = 1;

    private final ReviewRepository reviewRepository;
    private final EvidenceVerificationClient verificationClient;
    private final PlatformTransactionManager transactionManager;
    private final ReviewRatingEventPublisher ratingEventPublisher;

    public static Instant endOfVnDay(Instant base, long plusDays) {
        LocalDate baseDate = base.atZone(VN_ZONE).toLocalDate();
        return baseDate.plusDays(plusDays)
                .atTime(23, 59, 59, 999_000_000)
                .atZone(VN_ZONE)
                .toInstant();
    }

    @Transactional(readOnly = true)
    public ServiceReviewEligibilityResponse checkServiceReviewEligibility(String authorProfileId, UUID bookingId) {
        var existingOpt = reviewRepository.findBySourceId(bookingId);
        if (existingOpt.isPresent()) {
            ReviewEntity existing = existingOpt.get();
            if ("DELETED".equals(existing.getStatus())) {
                return new ServiceReviewEligibilityResponse(false, "PERMANENTLY_DELETED", null, null, null);
            }
            if ("HIDDEN".equals(existing.getStatus())) {
                return new ServiceReviewEligibilityResponse(false, "HIDDEN_BY_ADMIN", null, null, null);
            }
            ReviewResponse visible = authorProfileId.equals(existing.getAuthorProfileId())
                    ? toResponse(existing)
                    : null;
            return new ServiceReviewEligibilityResponse(false, "ALREADY_REVIEWED", null, null, visible);
        }

        BookingEligibility eligibility = verificationClient.verifyBookingEligible(bookingId, authorProfileId);
        if (eligibility.warrantyRework()) {
            return new ServiceReviewEligibilityResponse(
                    false, "WARRANTY_REWORK_INELIGIBLE", eligibility.acceptedAt(), null, null);
        }
        if (!eligibility.eligible() || eligibility.providerId() == null) {
            return new ServiceReviewEligibilityResponse(false, "NOT_ACCEPTED", eligibility.acceptedAt(), null, null);
        }
        Instant acceptedAt = eligibility.acceptedAt() != null ? eligibility.acceptedAt() : Instant.now();
        Instant deadline = endOfVnDay(acceptedAt, CREATE_WINDOW_DAYS);
        if (Instant.now().isAfter(deadline)) {
            return new ServiceReviewEligibilityResponse(false, "WINDOW_EXPIRED", acceptedAt, deadline, null);
        }
        return new ServiceReviewEligibilityResponse(true, "ELIGIBLE", acceptedAt, deadline, null);
    }

    public ReviewResponse createServiceReview(String authorProfileId, CreateServiceReviewRequest request) {
        return createServiceReview(authorProfileId, null, request);
    }

    public ReviewResponse createServiceReview(
            String authorProfileId, String authorName, CreateServiceReviewRequest request) {
        var preExisting = reviewRepository.findBySourceId(request.bookingId());
        if (preExisting.isPresent()) {
            if ("DELETED".equals(preExisting.get().getStatus())) {
                throw new DomainException(ReviewErrorCode.REVIEW_PERMANENTLY_DELETED);
            }
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }

        BookingEligibility eligibility = verificationClient.verifyBookingEligible(
                request.bookingId(), authorProfileId);
        if (!eligibility.eligible() || eligibility.providerId() == null || eligibility.warrantyRework()) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_ELIGIBLE);
        }
        if (request.providerId() != null && !request.providerId().equals(eligibility.providerId())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "providerId does not match booking provider.");
        }
        Instant now = Instant.now();
        if (eligibility.acceptedAt() != null
                && now.isAfter(endOfVnDay(eligibility.acceptedAt(), CREATE_WINDOW_DAYS))) {
            throw new DomainException(ReviewErrorCode.REVIEW_WINDOW_EXPIRED);
        }

        UUID providerId = eligibility.providerId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            return tx.execute(status -> {
                var existing = reviewRepository.findBySourceId(request.bookingId());
                if (existing.isPresent()) {
                    if ("DELETED".equals(existing.get().getStatus())) {
                        throw new DomainException(ReviewErrorCode.REVIEW_PERMANENTLY_DELETED);
                    }
                    throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
                }
                Instant txNow = Instant.now();
                ReviewEntity review = ReviewEntity.builder()
                        .id(UUID.randomUUID())
                        .targetType("SERVICE_BOOKING")
                        .sourceId(request.bookingId())
                        .targetId(providerId)
                        .authorProfileId(authorProfileId)
                        .authorName(authorName)
                        .rating(request.rating())
                        .comment(request.comment().trim())
                        .status(EntityStatus.PUBLISHED.name())
                        .editCount(0)
                        .createdAt(txNow)
                        .updatedAt(txNow)
                        .build();
                ReviewEntity saved = reviewRepository.saveAndFlush(review);
                ratingEventPublisher.scheduleAfterCommitPublish(providerId);
                return toResponse(saved);
            });
        } catch (DataIntegrityViolationException ex) {
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }
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
        return saveOrReuseProductReviewInTx(
                request.orderLineId(),
                productId,
                authorProfileId,
                authorName,
                request.rating(),
                request.comment());
    }

    private ReviewResponse saveOrReuseProductReviewInTx(
            UUID sourceId,
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
                        .targetType("PRODUCT_ORDER_LINE")
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

    private ReviewEntity requireServiceReviewForAuthor(String authorProfileId, UUID reviewId) {
        ReviewEntity r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!"SERVICE_BOOKING".equalsIgnoreCase(r.getTargetType())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Cannot update non-service review via service review endpoint.");
        }
        if (!r.getAuthorProfileId().equals(authorProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if ("HIDDEN".equals(r.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_HIDDEN);
        }
        if (!"PUBLISHED".equals(r.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND);
        }
        return r;
    }

    @Transactional
    public ReviewResponse updateServiceReview(
            String authorProfileId,
            UUID reviewId,
            String ifMatch,
            UpdateReviewRequest request) {
        ReviewEntity r = requireServiceReviewForAuthor(authorProfileId, reviewId);
        EtagSupport.verifyIfMatch(ifMatch, r.getVersion());
        if (r.getEditCount() >= MAX_EDIT_COUNT) {
            throw new DomainException(ReviewErrorCode.REVIEW_EDIT_LIMIT_EXCEEDED);
        }
        Instant now = Instant.now();
        if (now.isAfter(endOfVnDay(r.getCreatedAt(), EDIT_WINDOW_DAYS))) {
            throw new DomainException(ReviewErrorCode.REVIEW_EDIT_WINDOW_EXPIRED);
        }
        boolean hasRating = request.rating() != null;
        boolean hasComment = request.comment() != null && !request.comment().isBlank();
        if (!hasRating && !hasComment) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "At least one of rating or non-blank comment must be provided.");
        }
        if (hasRating) {
            r.setRating(request.rating());
        }
        if (hasComment) {
            r.setComment(request.comment().trim());
        }
        r.setEditCount(r.getEditCount() + 1);
        r.setEditedAt(now);
        r.setUpdatedAt(now);
        ReviewEntity saved = reviewRepository.saveAndFlush(r);
        ratingEventPublisher.scheduleAfterCommitPublish(saved.getTargetId());
        return toResponse(saved);
    }

    @Transactional
    public void deleteServiceReview(String authorProfileId, UUID reviewId, String ifMatch) {
        ReviewEntity r = requireServiceReviewForAuthor(authorProfileId, reviewId);
        EtagSupport.verifyIfMatch(ifMatch, r.getVersion());
        r.setStatus("DELETED");
        r.setUpdatedAt(Instant.now());
        ReviewEntity saved = reviewRepository.saveAndFlush(r);
        ratingEventPublisher.scheduleAfterCommitPublish(saved.getTargetId());
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

    @Transactional(readOnly = true)
    public PageResponse<AdminReviewResponse> adminListServiceReviews(
            String status, UUID providerId, Integer rating, String q, int page, int size) {
        PageResponse.validate(page, size);
        String normalizedStatus = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null
                : status.trim().toUpperCase();
        String normalizedQ = (q == null || q.isBlank()) ? null : q.trim();
        var pageable = PageRequest.of(page - 1, size);
        var result = reviewRepository.findAdminServiceReviews(
                normalizedStatus, providerId, rating, normalizedQ, pageable);
        return PageResponse.of(result.map(this::toAdminResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional
    public AdminReviewResponse adminHideServiceReview(
            String adminProfileId, UUID reviewId, String ifMatch, String reason) {
        ReviewEntity r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!"SERVICE_BOOKING".equalsIgnoreCase(r.getTargetType())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Only service reviews can be moderated via service review moderation.");
        }
        EtagSupport.verifyIfMatch(ifMatch, r.getVersion());
        if (!"PUBLISHED".equals(r.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_INVALID_STATE,
                    "Only PUBLISHED reviews can be hidden.");
        }
        Instant now = Instant.now();
        r.setStatus("HIDDEN");
        r.setHiddenReason(reason.trim());
        r.setHiddenBy(adminProfileId);
        r.setHiddenAt(now);
        r.setUpdatedAt(now);
        ReviewEntity saved = reviewRepository.saveAndFlush(r);
        ratingEventPublisher.scheduleAfterCommitPublish(saved.getTargetId());
        return toAdminResponse(saved);
    }

    @Transactional
    public AdminReviewResponse adminRestoreServiceReview(
            String adminProfileId, UUID reviewId, String ifMatch, String note) {
        ReviewEntity r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!"SERVICE_BOOKING".equalsIgnoreCase(r.getTargetType())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Only service reviews can be moderated via service review moderation.");
        }
        EtagSupport.verifyIfMatch(ifMatch, r.getVersion());
        if (!"HIDDEN".equals(r.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_INVALID_STATE,
                    "Only HIDDEN reviews can be restored.");
        }
        Instant now = Instant.now();
        r.setStatus(EntityStatus.PUBLISHED.name());
        r.setRestoredBy(adminProfileId);
        r.setRestoredAt(now);
        r.setRestoreNote(note != null && !note.isBlank() ? note.trim() : null);
        r.setUpdatedAt(now);
        ReviewEntity saved = reviewRepository.saveAndFlush(r);
        ratingEventPublisher.scheduleAfterCommitPublish(saved.getTargetId());
        return toAdminResponse(saved);
    }

    public AdminResyncRatingResponse adminResyncRatings(UUID providerId) {
        List<ProviderRatingSnapshot> snapshots = ratingEventPublisher.resyncProviderRatings(providerId);
        return new AdminResyncRatingResponse(snapshots.size(), snapshots);
    }

    private ReviewResponse toResponse(ReviewEntity r) {
        Instant editableUntil = r.getCreatedAt() != null
                ? endOfVnDay(r.getCreatedAt(), EDIT_WINDOW_DAYS)
                : null;
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
                r.getEditCount(),
                r.getEditedAt(),
                editableUntil,
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private AdminReviewResponse toAdminResponse(ReviewEntity r) {
        return new AdminReviewResponse(
                r.getId(),
                r.getSourceId(),
                r.getTargetId(),
                r.getAuthorProfileId(),
                r.getAuthorName(),
                r.getRating(),
                r.getComment(),
                r.getStatus(),
                r.getEditCount(),
                r.getEditedAt(),
                r.getHiddenReason(),
                r.getHiddenBy(),
                r.getHiddenAt(),
                r.getRestoredBy(),
                r.getRestoredAt(),
                r.getRestoreNote(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private ReviewEntity requireProductReview(String user, UUID productId, UUID reviewId) {
        var review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!"PRODUCT_ORDER_LINE".equals(review.getTargetType())
                || !productId.equals(review.getTargetId())
                || !"PUBLISHED".equals(review.getStatus())) {
            throw new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND);
        }
        if (!user.equals(review.getAuthorProfileId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return review;
    }

    @Transactional
    public ReviewResponse updateProductReview(String user, UUID productId, UUID reviewId, UpdateReviewRequest input) {
        var review = requireProductReview(user, productId, reviewId);
        if (input.rating() != null) {
            review.setRating(input.rating());
        }
        if (input.comment() != null && !input.comment().isBlank()) {
            review.setComment(input.comment().trim());
        }
        review.setUpdatedAt(Instant.now());
        return toResponse(reviewRepository.save(review));
    }

    @Transactional
    public void deleteProductReview(String user, UUID productId, UUID reviewId) {
        var review = requireProductReview(user, productId, reviewId);
        review.setStatus("DELETED");
        review.setUpdatedAt(Instant.now());
        reviewRepository.save(review);
    }
}
