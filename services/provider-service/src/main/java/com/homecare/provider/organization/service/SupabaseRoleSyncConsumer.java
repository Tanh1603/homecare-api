package com.homecare.provider.organization.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.repository.TechnicianRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SupabaseRoleSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(SupabaseRoleSyncConsumer.class);
    public static final String QUEUE = "provider.supabase.role.sync.q";

    private final OrganizationMemberRepository memberRepository;
    private final TechnicianRepository technicianRepository;
    private final SupabaseRoleClient supabaseRoleClient;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @SuppressWarnings("unchecked")
    public void onOrganizationEvent(String payloadJson) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payloadJson, Map.class);
            String eventType = String.valueOf(envelope.get(DomainEventContracts.FIELD_EVENT_TYPE));
            Object dataObj = envelope.get(DomainEventContracts.FIELD_DATA);
            if (!(dataObj instanceof Map<?, ?> dataMap)) {
                return;
            }
            Object affectedObj = dataMap.get("affectedUserIds");
            if (!(affectedObj instanceof List<?> userIds)) {
                return;
            }
            for (Object idObj : userIds) {
                if (idObj != null) {
                    syncUserGlobalOrganizationRole(String.valueOf(idObj), eventType);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to process organization role sync event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Role sync processing failed", ex);
        }
    }

    public void syncUserGlobalOrganizationRole(String userId, String triggerEvent) {
        long approvedMemberships = memberRepository.countApprovedMembershipsByUserId(userId);
        if (approvedMemberships > 0) {
            log.info("[AUDIT-ROLE-SYNC] trigger={} userId={} approvedMemberships={} -> GRANT ORGANIZATION",
                    triggerEvent, userId, approvedMemberships);
            supabaseRoleClient.grantOrganizationRole(userId);
        } else {
            log.info("[AUDIT-ROLE-SYNC] trigger={} userId={} approvedMemberships=0 -> REVOKE ORGANIZATION",
                    triggerEvent, userId);
            supabaseRoleClient.revokeOrganizationRole(userId);
        }
        syncUserGlobalTechnicianRole(userId, triggerEvent);
    }

    public void syncUserGlobalTechnicianRole(String userId, String triggerEvent) {
        boolean isActiveTechnician = technicianRepository.existsActiveTechnicianInActiveProvider(userId)
                || memberRepository.countApprovedTechnicianMembershipsByUserId(userId) > 0;
        if (isActiveTechnician) {
            log.info("[AUDIT-ROLE-SYNC] trigger={} userId={} -> GRANT TECHNICIAN", triggerEvent, userId);
            supabaseRoleClient.grantTechnicianRole(userId);
        } else {
            log.info("[AUDIT-ROLE-SYNC] trigger={} userId={} -> REVOKE TECHNICIAN", triggerEvent, userId);
            supabaseRoleClient.revokeTechnicianRole(userId);
        }
    }
}
