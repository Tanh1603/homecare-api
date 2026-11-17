package com.homecare.notification.security;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import org.springframework.stereotype.Component;

/**
 * Notification domain access policy enforcing personal recipient mode.
 */
@Component
public class NotificationAccessPolicy {

    public void assertPersonalRecipient(ActorContext actor) {
        if (actor.mode() != AccessMode.PERSONAL) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
    }
}
