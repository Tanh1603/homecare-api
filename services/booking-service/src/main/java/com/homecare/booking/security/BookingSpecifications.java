package com.homecare.booking.security;

import com.homecare.booking.entity.BookingEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specifications enforcing {@link BookingReadScope} at the database query level.
 */
public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    public static Specification<BookingEntity> withScope(BookingReadScope scope, String status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (!scope.globalAdmin()) {
                List<Predicate> visibilityOr = new ArrayList<>();
                if (scope.requesterProfileId() != null) {
                    visibilityOr.add(cb.equal(root.get("requesterProfileId"), scope.requesterProfileId()));
                }
                if (scope.assignedTechnicianProfileId() != null) {
                    Predicate techMatch = cb.equal(
                            root.get("assignedTechnicianProfileId"), scope.assignedTechnicianProfileId());
                    if (scope.organizationId() != null) {
                        visibilityOr.add(cb.and(
                                techMatch, cb.equal(root.get("providerId"), scope.organizationId())));
                    } else {
                        visibilityOr.add(techMatch);
                    }
                }
                if (scope.includeOrganizationBookings() && scope.organizationId() != null) {
                    visibilityOr.add(cb.equal(root.get("providerId"), scope.organizationId()));
                }
                if (visibilityOr.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(cb.or(visibilityOr.toArray(Predicate[]::new)));
                }
            } else if (scope.organizationId() != null) {
                predicates.add(cb.equal(root.get("providerId"), scope.organizationId()));
            }

            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
