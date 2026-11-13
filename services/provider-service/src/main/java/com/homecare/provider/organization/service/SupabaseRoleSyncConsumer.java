package com.homecare.provider.organization.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.homecare.provider.organization.repository.OrganizationMemberRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SupabaseRoleSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(SupabaseRoleSyncConsumer.class);
    public static final String QUEUE = "provider.supabase.role.sync.q";

    private final OrganizationMemberRepository memberRepository;
    private final SupabaseRoleClient supabaseRoleClient;
    private final ObjectMapper objectMapper;

    @Bean
    public TopicExchange organizationDomainExchange() {
        return new TopicExchange(OrganizationDomainService.EXCHANGE, true, false);
    }

    @Bean
    public Queue supabaseRoleSyncQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding bindOrgApprovedForRoleSync(Queue supabaseRoleSyncQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(supabaseRoleSyncQueue)
                .to(organizationDomainExchange)
                .with(OrganizationDomainService.EVT_APPROVED);
    }

    @Bean
    public Binding bindOrgSuspendedForRoleSync(Queue supabaseRoleSyncQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(supabaseRoleSyncQueue)
                .to(organizationDomainExchange)
                .with(OrganizationDomainService.EVT_SUSPENDED);
    }

    @Bean
    public Binding bindOrgMemberChangedForRoleSync(Queue supabaseRoleSyncQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(supabaseRoleSyncQueue)
                .to(organizationDomainExchange)
                .with(OrganizationDomainService.EVT_MEMBER_CHANGED);
    }

    @RabbitListener(queues = QUEUE)
    @SuppressWarnings("unchecked")
    public void onOrganizationEvent(String payloadJson) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payloadJson, Map.class);
            String eventType = String.valueOf(envelope.get("eventType"));
            Object dataObj = envelope.get("data");
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
    }
}
