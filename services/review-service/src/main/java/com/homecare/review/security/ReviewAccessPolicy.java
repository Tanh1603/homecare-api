package com.homecare.review.security;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import org.springframework.stereotype.Component;

/**
 * Review domain access policy enforcing personal-mode customer mutations.
 */
@Component
public class ReviewAccessPolicy {

    public void assertCanMutatePersonalReview(ActorContext actor) {
        if (actor.mode() != AccessMode.PERSONAL) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
    }
}
