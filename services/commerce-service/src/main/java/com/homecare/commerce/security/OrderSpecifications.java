package com.homecare.commerce.security;

import com.homecare.commerce.entity.OrderEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specifications enforcing {@link OrderReadScope} at the database query level.
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<OrderEntity> withScope(OrderReadScope scope, String status, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (!scope.globalAdmin()) {
                if (scope.merchantId() != null) {
                    predicates.add(cb.equal(root.get("merchantId"), scope.merchantId()));
                } else if (scope.buyerProfileId() != null) {
                    predicates.add(cb.equal(root.get("buyerProfileId"), scope.buyerProfileId()));
                } else {
                    predicates.add(cb.disjunction());
                }
            }

            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("shippingAddress")), "%" + q.trim().toLowerCase() + "%"));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
