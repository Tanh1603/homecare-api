package com.homecare.provider.organization.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.dto.OrganizationDtos.AddMemberRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.MyOrganizationMembershipResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationMemberResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationEntity;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.ProviderRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OrganizationDomainService {

    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;
    public static final String EVT_APPROVED = DomainEventContracts.ORG_APPROVED;
    public static final String EVT_SUSPENDED = DomainEventContracts.ORG_SUSPENDED;
    public static final String EVT_MEMBER_CHANGED = DomainEventContracts.ORG_MEMBER_CHANGED;

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final ProviderOutboxEventRepository outboxRepository;
    private final ProviderRepository providerRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public OrganizationResponse registerOrganization(String userId, RegisterOrganizationRequest req) {
        Instant now = Instant.now();
        UUID orgId = UUID.randomUUID();

        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .id(orgId)
                .name(req.name().trim())
                .type(req.type())
                .status(OrganizationStatus.PENDING)
                .taxCode(req.taxCode() != null ? req.taxCode().trim() : null)
                .phone(req.phone().trim())
                .city(req.city().trim())
                .address(req.address() != null ? req.address().trim() : null)
                .description(req.description() != null ? req.description().trim() : null)
                .ownerUserId(userId)
                .createdAt(now)
                .updatedAt(now)
                .build());

        memberRepository.save(OrganizationMemberEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .userId(userId)
                .role(OrgRole.ORG_OWNER)
                .status("ACTIVE")
                .joinedAt(now)
                .createdAt(now)
                .build());

        return toOrgResponse(org);
    }

    @Transactional(readOnly = true)
    public List<MyOrganizationMembershipResponse> listMyOrganizations(String userId) {
        List<OrganizationMemberEntity> memberships = memberRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<UUID, OrganizationEntity> orgsById = new LinkedHashMap<>();
        organizationRepository.findAllById(
                memberships.stream().map(OrganizationMemberEntity::getOrganizationId).toList())
                .forEach(o -> orgsById.put(o.getId(), o));

        return memberships.stream()
                .filter(m -> orgsById.containsKey(m.getOrganizationId()))
                .map(m -> {
                    OrganizationEntity org = orgsById.get(m.getOrganizationId());
                    return new MyOrganizationMembershipResponse(
                            org.getId(),
                            org.getName(),
                            org.getType(),
                            org.getStatus(),
                            org.getRejectReason(),
                            m.getRole(),
                            m.getStatus(),
                            m.getJoinedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganizationDetail(UUID orgId, String userId, boolean isAdmin) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (!isAdmin) {
            OrganizationMemberEntity member = memberRepository.findByOrganizationIdAndUserId(orgId, userId)
                    .orElseThrow(() -> new DomainException(ProviderErrorCode.ORG_MEMBERSHIP_REQUIRED));
            if (!"ACTIVE".equalsIgnoreCase(member.getStatus())) {
                throw new DomainException(ProviderErrorCode.ORG_MEMBERSHIP_REQUIRED);
            }
        }
        return toOrgResponse(org);
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> listAllForAdmin(OrganizationStatus statusFilter) {
        List<OrganizationEntity> list = statusFilter != null
                ? organizationRepository.findByStatusOrderByCreatedAtDesc(statusFilter)
                : organizationRepository.findAllByOrderByCreatedAtDesc();
        return list.stream().map(this::toOrgResponse).toList();
    }

    @Transactional
    public OrganizationResponse approveOrganization(UUID orgId, String adminUserId) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getStatus() == OrganizationStatus.APPROVED) {
            return toOrgResponse(org);
        }
        if (org.getStatus() == OrganizationStatus.REJECTED) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE);
        }

        Instant now = Instant.now();
        org.setStatus(OrganizationStatus.APPROVED);
        org.setRejectReason(null);
        org.setUpdatedAt(now);
        OrganizationEntity saved = organizationRepository.save(org);

        if (saved.getType() == OrganizationType.SERVICE || saved.getType() == OrganizationType.BOTH) {
            if (!providerRepository.existsByOrganizationId(saved.getId())) {
                providerRepository.save(ProviderEntity.builder()
                        .id(saved.getId())
                        .organizationId(saved.getId())
                        .ownerProfileId(saved.getOwnerUserId())
                        .name(saved.getName())
                        .description(saved.getDescription())
                        .city(saved.getCity())
                        .phone(saved.getPhone())
                        .ratingAvg(0.0)
                        .status("INACTIVE")
                        .createdAt(now)
                        .build());
            }
        }

        List<String> activeMemberIds = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .map(OrganizationMemberEntity::getUserId)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("name", saved.getName());
        data.put("type", saved.getType().name());
        data.put("ownerUserId", saved.getOwnerUserId());
        data.put("city", saved.getCity());
        data.put("phone", saved.getPhone());
        data.put("approvedBy", adminUserId);
        data.put("affectedUserIds", activeMemberIds);

        enqueueOutbox(saved.getId(), EVT_APPROVED, data, now);
        return toOrgResponse(saved);
    }

    @Transactional
    public OrganizationResponse rejectOrganization(UUID orgId, String reason) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getStatus() != OrganizationStatus.PENDING) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Only PENDING organizations can be rejected.");
        }
        org.setStatus(OrganizationStatus.REJECTED);
        org.setRejectReason(reason != null ? reason.trim() : null);
        org.setUpdatedAt(Instant.now());
        return toOrgResponse(organizationRepository.save(org));
    }

    @Transactional
    public OrganizationResponse suspendOrganization(UUID orgId) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getStatus() == OrganizationStatus.SUSPENDED) {
            return toOrgResponse(org);
        }

        Instant now = Instant.now();
        org.setStatus(OrganizationStatus.SUSPENDED);
        org.setUpdatedAt(now);
        OrganizationEntity saved = organizationRepository.save(org);

        providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
            provider.setStatus("INACTIVE");
            providerRepository.save(provider);
        });

        List<String> activeMemberIds = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .map(OrganizationMemberEntity::getUserId)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("affectedUserIds", activeMemberIds);

        enqueueOutbox(saved.getId(), EVT_SUSPENDED, data, now);
        return toOrgResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> listMembers(UUID orgId) {
        return memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public OrganizationMemberResponse addMember(UUID orgId, AddMemberRequest request) {
        Instant now = Instant.now();
        String targetUserId = request.userId().trim();
        OrganizationMemberEntity existing = memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId)
                .orElse(null);

        OrganizationMemberEntity saved;
        if (existing != null) {
            if ("ACTIVE".equalsIgnoreCase(existing.getStatus())) {
                throw new DomainException(ProviderErrorCode.MEMBER_ALREADY_EXISTS);
            }
            existing.setRole(request.role());
            existing.setStatus("ACTIVE");
            existing.setJoinedAt(now);
            saved = memberRepository.save(existing);
        } else {
            saved = memberRepository.save(OrganizationMemberEntity.builder()
                    .id(UUID.randomUUID())
                    .organizationId(orgId)
                    .userId(targetUserId)
                    .role(request.role())
                    .status("ACTIVE")
                    .joinedAt(now)
                    .createdAt(now)
                    .build());
        }

        Map<String, Object> data = Map.of(
                "organizationId", orgId.toString(),
                "affectedUserIds", List.of(targetUserId));
        enqueueOutbox(orgId, EVT_MEMBER_CHANGED, data, now);
        return toMemberResponse(saved);
    }

    @Transactional
    public OrganizationMemberResponse updateMemberRole(UUID orgId, String targetUserId, OrgRole newRole) {
        OrganizationMemberEntity member = requireActiveModifiableMember(orgId, targetUserId);
        member.setRole(newRole);
        return toMemberResponse(memberRepository.save(member));
    }

    @Transactional
    public void removeMember(UUID orgId, String targetUserId) {
        OrganizationMemberEntity member = requireActiveModifiableMember(orgId, targetUserId);
        member.setStatus("INACTIVE");
        memberRepository.save(member);

        Map<String, Object> data = Map.of(
                "organizationId", orgId.toString(),
                "affectedUserIds", List.of(targetUserId));
        enqueueOutbox(orgId, EVT_MEMBER_CHANGED, data, Instant.now());
    }

    private OrganizationMemberEntity requireActiveModifiableMember(UUID orgId, String targetUserId) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        OrganizationMemberEntity member = memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId)
                .filter(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORG_MEMBERSHIP_REQUIRED));
        if (org.getOwnerUserId().equals(targetUserId) || member.getRole() == OrgRole.ORG_OWNER) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN);
        }
        return member;
    }

    private void enqueueOutbox(UUID aggregateId, String eventType, Map<String, Object> data, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId.toString());
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "provider-service");
        envelope.put(DomainEventContracts.FIELD_DATA, data);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize organization outbox event", e);
        }

        outboxRepository.save(ProviderOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("Organization")
                .aggregateId(aggregateId)
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(json)
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    private OrganizationResponse toOrgResponse(OrganizationEntity org) {
        return new OrganizationResponse(
                org.getId(),
                org.getName(),
                org.getType(),
                org.getStatus(),
                org.getRejectReason(),
                org.getTaxCode(),
                org.getPhone(),
                org.getCity(),
                org.getAddress(),
                org.getDescription(),
                org.getOwnerUserId(),
                org.getVersion(),
                org.getCreatedAt(),
                org.getUpdatedAt());
    }

    private OrganizationMemberResponse toMemberResponse(OrganizationMemberEntity m) {
        return new OrganizationMemberResponse(
                m.getId(),
                m.getOrganizationId(),
                m.getUserId(),
                m.getRole(),
                m.getStatus(),
                m.getJoinedAt());
    }
}
