package com.homecare.provider.organization.service;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.dto.OrganizationDtos.AddMemberRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.MyOrganizationMembershipResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationMemberResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.ResubmitOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.UpdateOrganizationRequest;
import com.homecare.provider.organization.entity.LegalEntityType;
import com.homecare.provider.organization.entity.OrganizationAppealEntity;
import com.homecare.provider.organization.entity.OrganizationEnforcementEntity;
import com.homecare.provider.organization.entity.OrganizationEntity;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.OrganizationAppealRepository;
import com.homecare.provider.organization.repository.OrganizationEnforcementRepository;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.organization.security.OrgContext;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.service.PlatformGovernanceService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OrganizationDomainService {

    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;
    public static final String EVT_APPROVED = DomainEventContracts.ORG_APPROVED;
    public static final String EVT_UPDATED = DomainEventContracts.ORG_UPDATED;
    public static final String EVT_SUSPENDED = DomainEventContracts.ORG_SUSPENDED;
    public static final String EVT_BANNED = DomainEventContracts.ORG_BANNED;
    public static final String EVT_MEMBER_CHANGED = DomainEventContracts.ORG_MEMBER_CHANGED;

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationEnforcementRepository enforcementRepository;
    private final OrganizationAppealRepository appealRepository;
    private final ProviderOutboxEventRepository outboxRepository;
    private final ProviderRepository providerRepository;
    private final SupabaseRoleClient supabaseRoleClient;
    private final PlatformGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    @Transactional
    public OrganizationResponse registerOrganization(String userId, RegisterOrganizationRequest req) {
        if (req.type() != OrganizationType.SERVICE) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Only SERVICE organizations are accepted in v0.1.");
        }
        if (organizationRepository.existsByOwnerUserIdAndStatus(userId, OrganizationStatus.BANNED)) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "Permanently banned organization owners cannot register a new organization.");
        }
        Instant now = Instant.now();
        UUID orgId = UUID.randomUUID();

        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .id(orgId)
                .name(req.name().trim())
                .type(req.type())
                .legalEntityType(req.legalEntityType())
                .status(OrganizationStatus.PENDING)
                .resubmitCount(0)
                .identityVerified(false)
                .identityEvidenceRefs(req.identityEvidenceRefs())
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
                .status(EntityStatus.ACTIVE.name())
                .joinedAt(now)
                .createdAt(now)
                .build());

        return toOrgResponse(org);
    }

    @Transactional
    public OrganizationResponse resubmitOrganization(
            UUID orgId, String userId, String ifMatch, ResubmitOrganizationRequest req) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (!org.getOwnerUserId().equals(userId)) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN,
                    "Only the organization owner can resubmit a rejected organization.");
        }
        if (org.getStatus() != OrganizationStatus.REJECTED) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Only REJECTED organizations can be resubmitted.");
        }
        if (ifMatch != null && !ifMatch.isBlank()) {
            EtagSupport.verifyIfMatch(ifMatch, org.getVersion());
        }
        int maxResubmit = governanceService.getOrganizationMaxResubmitCount();
        if (org.getResubmitCount() >= maxResubmit) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Maximum resubmission limit (" + maxResubmit + ") reached.");
        }
        org.setName(req.name().trim());
        if (req.legalEntityType() != null) {
            org.setLegalEntityType(req.legalEntityType());
        }
        org.setTaxCode(req.taxCode() != null ? req.taxCode().trim() : null);
        org.setPhone(req.phone().trim());
        org.setCity(req.city().trim());
        org.setAddress(req.address() != null ? req.address().trim() : null);
        org.setDescription(req.description() != null ? req.description().trim() : null);
        if (req.identityEvidenceRefs() != null) {
            org.setIdentityEvidenceRefs(req.identityEvidenceRefs());
        }
        org.setStatus(OrganizationStatus.PENDING);
        org.setResubmitCount(org.getResubmitCount() + 1);
        org.setUpdatedAt(Instant.now());
        return toOrgResponse(organizationRepository.save(org));
    }

    @Transactional(readOnly = true)
    public PageResponse<MyOrganizationMembershipResponse> listMyOrganizationsPage(String userId, int page, int size) {
        return slicePage(listMyOrganizations(userId), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> listAllForAdminPage(OrganizationStatus statusFilter, int page, int size) {
        return slicePage(listAllForAdmin(statusFilter), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrganizationMemberResponse> listMembersPage(UUID orgId, int page, int size) {
        return slicePage(listMembers(orgId), page, size);
    }

    private static <T> PageResponse<T> slicePage(List<T> all, int page, int size) {
        PageResponse.validate(page, size);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return PageResponse.of(all.subList(from, to), page, size, all.size());
    }

    @Transactional(readOnly = true)
    public List<MyOrganizationMembershipResponse> listMyOrganizations(String userId) {
        List<OrganizationMemberEntity> memberships = memberRepository.findByUserIdAndStatus(userId, EntityStatus.ACTIVE.name());
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
                            org.getLegalEntityType(),
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
            if (!EntityStatus.ACTIVE.name().equalsIgnoreCase(member.getStatus())) {
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
            providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
                provider.setStatus(EntityStatus.ACTIVE.name());
                providerRepository.save(provider);
            });
            return toOrgResponse(org);
        }
        if (org.getStatus() == OrganizationStatus.REJECTED
                || org.getStatus() == OrganizationStatus.BANNED
                || org.getStatus() == OrganizationStatus.SUSPENDED) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE);
        }

        Instant now = Instant.now();
        org.setStatus(OrganizationStatus.APPROVED);
        org.setIdentityVerified(true);
        org.setIdentityVerifiedBy(adminUserId);
        org.setIdentityVerifiedAt(now);
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
                        .status(EntityStatus.ACTIVE.name())
                        .createdAt(now)
                        .build());
            } else {
                providerRepository.findByOrganizationId(saved.getId()).ifPresent(provider -> {
                    provider.setStatus(EntityStatus.ACTIVE.name());
                    providerRepository.save(provider);
                });
            }
        }

        List<String> activeMemberIds = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                .map(OrganizationMemberEntity::getUserId)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("name", saved.getName());
        data.put("type", saved.getType().name());
        if (saved.getLegalEntityType() != null) {
            data.put("legalEntityType", saved.getLegalEntityType().name());
        }
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
        return suspendOrganization(orgId, "Admin administrative suspension", null, false, "SYSTEM");
    }

    @Transactional
    public OrganizationResponse suspendOrganization(
            UUID orgId, String reason, String evidenceRefs, boolean safetyInterventionFlag, String adminUserId) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Suspension reason is required.");
        }
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getStatus() == OrganizationStatus.SUSPENDED) {
            return toOrgResponse(org);
        }
        if (org.getStatus() != OrganizationStatus.APPROVED) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Only APPROVED organizations can be suspended.");
        }

        Instant now = Instant.now();
        org.setStatus(OrganizationStatus.SUSPENDED);
        org.setUpdatedAt(now);
        OrganizationEntity saved = organizationRepository.save(org);

        providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
            provider.setStatus(EntityStatus.INACTIVE.name());
            providerRepository.save(provider);
        });

        enforcementRepository.save(OrganizationEnforcementEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .actionType("SUSPENDED")
                .reason(reason.trim())
                .evidenceRefs(evidenceRefs)
                .safetyInterventionFlag(safetyInterventionFlag)
                .issuedByAdmin(adminUserId)
                .issuedAt(now)
                .build());

        List<String> activeMemberIds = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                .map(OrganizationMemberEntity::getUserId)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("providerId", saved.getId().toString());
        data.put("reason", reason.trim());
        data.put("safetyInterventionFlag", safetyInterventionFlag);
        data.put("affectedUserIds", activeMemberIds);

        enqueueOutbox(saved.getId(), EVT_SUSPENDED, data, now);
        return toOrgResponse(saved);
    }

    @Transactional
    public OrganizationResponse banOrganization(
            UUID orgId, String reason, String evidenceRefs, boolean safetyInterventionFlag, String adminUserId) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Ban reason is required.");
        }
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getStatus() == OrganizationStatus.BANNED) {
            return toOrgResponse(org);
        }
        if (org.getStatus() != OrganizationStatus.APPROVED && org.getStatus() != OrganizationStatus.SUSPENDED) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Only APPROVED or SUSPENDED organizations can be permanently banned.");
        }

        Instant now = Instant.now();
        org.setStatus(OrganizationStatus.BANNED);
        org.setUpdatedAt(now);
        OrganizationEntity saved = organizationRepository.save(org);

        providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
            provider.setStatus(EntityStatus.INACTIVE.name());
            providerRepository.save(provider);
        });

        enforcementRepository.save(OrganizationEnforcementEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .actionType("BANNED")
                .reason(reason.trim())
                .evidenceRefs(evidenceRefs)
                .safetyInterventionFlag(safetyInterventionFlag)
                .issuedByAdmin(adminUserId)
                .issuedAt(now)
                .build());

        List<String> activeMemberIds = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                .map(OrganizationMemberEntity::getUserId)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("providerId", saved.getId().toString());
        data.put("reason", reason.trim());
        data.put("safetyInterventionFlag", safetyInterventionFlag);
        data.put("affectedUserIds", activeMemberIds);

        enqueueOutbox(saved.getId(), EVT_BANNED, data, now);
        return toOrgResponse(saved);
    }

    @Transactional
    public OrganizationAppealEntity submitAppeal(
            UUID orgId, String userId, UUID enforcementId, String appealReason, String evidenceRefs) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (!org.getOwnerUserId().equals(userId)) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN,
                    "Only ORG_OWNER can submit an enforcement appeal.");
        }
        if (appealReason == null || appealReason.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Appeal reason is required.");
        }
        OrganizationEnforcementEntity enforcement = enforcementRepository.findById(enforcementId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Enforcement record not found."));
        if (!enforcement.getOrganizationId().equals(orgId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Enforcement does not belong to this organization.");
        }
        Instant now = Instant.now();
        if (now.isAfter(enforcement.getIssuedAt().plusSeconds(14L * 86_400L))) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Appeal window (14 calendar days) has expired.");
        }
        long existingCount = appealRepository.countByEnforcementId(enforcementId);
        boolean hasNewEvidence = evidenceRefs != null && !evidenceRefs.isBlank();
        if (existingCount >= 1 && !hasNewEvidence) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Maximum 1 appeal per enforcement decision unless new evidence is attached.");
        }
        Instant slaDueAt = now.plusSeconds(7L * 86_400L); // 5 business days conservative calendar bound
        return appealRepository.save(OrganizationAppealEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .enforcementId(enforcementId)
                .attemptNumber((int) existingCount + 1)
                .appealReason(appealReason.trim())
                .evidenceRefs(evidenceRefs)
                .submittedBy(userId)
                .submittedAt(now)
                .slaDueAt(slaDueAt)
                .status("SUBMITTED")
                .build());
    }

    @Transactional
    public OrganizationAppealEntity resolveAppeal(
            UUID orgId, UUID appealId, boolean approved, String reviewNote, OrganizationStatus targetStatus, String adminUserId) {
        OrganizationAppealEntity appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Appeal not found."));
        if (!appeal.getOrganizationId().equals(orgId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Appeal does not belong to this organization.");
        }
        if (!"SUBMITTED".equals(appeal.getStatus())) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE, "Appeal is already resolved.");
        }
        OrganizationEnforcementEntity enforcement = enforcementRepository.findById(appeal.getEnforcementId())
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Enforcement record not found."));
        if (approved && "BANNED".equals(enforcement.getActionType())
                && adminUserId.equals(enforcement.getIssuedByAdmin())) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "A permanent ban can only be reversed by a different Admin (D-25).");
        }
        Instant now = Instant.now();
        appeal.setStatus(approved ? "APPROVED" : "REJECTED");
        appeal.setReviewNote(reviewNote != null ? reviewNote.trim() : null);
        appeal.setReviewedByAdmin(adminUserId);
        appeal.setReviewedAt(now);
        OrganizationAppealEntity savedAppeal = appealRepository.save(appeal);

        if (approved) {
            OrganizationEntity org = organizationRepository.findById(orgId)
                    .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
            OrganizationStatus next = targetStatus != null ? targetStatus : OrganizationStatus.APPROVED;
            if (next != OrganizationStatus.APPROVED && next != OrganizationStatus.SUSPENDED) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Appeal targetStatus must be APPROVED or SUSPENDED.");
            }
            org.setStatus(next);
            org.setUpdatedAt(now);
            organizationRepository.save(org);
            if (next == OrganizationStatus.APPROVED) {
                providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
                    provider.setStatus(EntityStatus.ACTIVE.name());
                    providerRepository.save(provider);
                });
            }
        }
        return savedAppeal;
    }

    @Transactional
    public OrganizationResponse reactivateOrganization(UUID orgId) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getStatus() == OrganizationStatus.APPROVED) {
            return toOrgResponse(org);
        }
        if (org.getStatus() != OrganizationStatus.SUSPENDED) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Only SUSPENDED organizations can be reactivated.");
        }

        Instant now = Instant.now();
        org.setStatus(OrganizationStatus.APPROVED);
        org.setRejectReason(null);
        org.setUpdatedAt(now);
        OrganizationEntity saved = organizationRepository.save(org);

        providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
            provider.setStatus(EntityStatus.ACTIVE.name());
            providerRepository.save(provider);
        });

        List<String> activeMemberIds = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                .map(OrganizationMemberEntity::getUserId)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("type", saved.getType().name());
        if (saved.getLegalEntityType() != null) {
            data.put("legalEntityType", saved.getLegalEntityType().name());
        }
        data.put("name", saved.getName());
        data.put("city", saved.getCity());
        data.put("ownerUserId", saved.getOwnerUserId());
        data.put("affectedUserIds", activeMemberIds);

        enqueueOutbox(saved.getId(), EVT_APPROVED, data, now);
        return toOrgResponse(saved);
    }

    @Transactional
    public OrganizationResponse updateCurrentOrganization(UUID orgId, String ifMatch, UpdateOrganizationRequest req) {
        if (req.name() == null && req.phone() == null && req.city() == null
                && req.address() == null && req.description() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "At least one field must be provided.");
        }
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (ifMatch != null && !ifMatch.isBlank()) {
            EtagSupport.verifyIfMatch(ifMatch, org.getVersion());
        }
        if (req.name() != null) org.setName(req.name().trim());
        if (req.phone() != null) org.setPhone(req.phone().trim());
        if (req.city() != null) org.setCity(req.city().trim());
        if (req.address() != null) org.setAddress(req.address().trim());
        if (req.description() != null) org.setDescription(req.description().trim());

        Instant now = Instant.now();
        org.setUpdatedAt(now);
        OrganizationEntity saved = organizationRepository.saveAndFlush(org);

        if (saved.getType() == OrganizationType.SERVICE || saved.getType() == OrganizationType.BOTH) {
            providerRepository.findByOrganizationId(orgId).ifPresent(provider -> {
                provider.setName(saved.getName());
                provider.setPhone(saved.getPhone());
                provider.setCity(saved.getCity());
                provider.setDescription(saved.getDescription());
                providerRepository.save(provider);
            });
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", saved.getId().toString());
        data.put("name", saved.getName());
        data.put("type", saved.getType().name());
        if (saved.getLegalEntityType() != null) {
            data.put("legalEntityType", saved.getLegalEntityType().name());
        }
        data.put("phone", saved.getPhone());
        data.put("city", saved.getCity());
        data.put("address", saved.getAddress());
        data.put("description", saved.getDescription());
        data.put("updatedAt", now.toString());
        enqueueOutbox(saved.getId(), EVT_UPDATED, data, now);
        return toOrgResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> listMembers(UUID orgId) {
        List<OrganizationMemberEntity> active = memberRepository.findByOrganizationIdOrderByJoinedAtAsc(orgId)
                .stream()
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                .toList();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<String, SupabaseRoleClient.ProfileSummary> profiles = supabaseRoleClient.fetchProfilesBatch(
                active.stream().map(OrganizationMemberEntity::getUserId).toList());
        return active.stream()
                .map(m -> toMemberResponse(m, profiles.get(m.getUserId())))
                .toList();
    }

    @Transactional
    public OrganizationMemberResponse addMember(UUID orgId, AddMemberRequest request) {
        if (request.role() == OrgRole.ORG_OWNER) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN, "Cannot assign ORG_OWNER role.");
        }
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getLegalEntityType() == LegalEntityType.INDIVIDUAL) {
            long activeCount = memberRepository.countByOrganizationIdAndStatus(orgId, EntityStatus.ACTIVE.name());
            if (activeCount >= 1) {
                throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN,
                        "INDIVIDUAL organizations are capped at 1 active member. Upgrade to HOUSEHOLD_BUSINESS or COMPANY first.");
            }
        }
        Instant now = Instant.now();
        String targetUserId = request.userId().trim();
        if (request.role() == OrgRole.ORG_TECHNICIAN
                && memberRepository.existsByUserIdAndRoleAndStatus(
                        targetUserId, OrgRole.ORG_TECHNICIAN, EntityStatus.ACTIVE.name())) {
            throw new DomainException(ProviderErrorCode.MEMBER_ALREADY_EXISTS,
                    "User is already an active ORG_TECHNICIAN in another organization (1:1 limit in v0.1).");
        }
        OrganizationMemberEntity existing = memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId)
                .orElse(null);

        OrganizationMemberEntity saved;
        if (existing != null) {
            if (EntityStatus.ACTIVE.name().equalsIgnoreCase(existing.getStatus())) {
                throw new DomainException(ProviderErrorCode.MEMBER_ALREADY_EXISTS);
            }
            existing.setRole(request.role());
            existing.setStatus(EntityStatus.ACTIVE.name());
            existing.setJoinedAt(now);
            saved = memberRepository.save(existing);
        } else {
            try {
                saved = memberRepository.saveAndFlush(OrganizationMemberEntity.builder()
                        .id(UUID.randomUUID())
                        .organizationId(orgId)
                        .userId(targetUserId)
                        .role(request.role())
                        .status(EntityStatus.ACTIVE.name())
                        .joinedAt(now)
                        .createdAt(now)
                        .build());
            } catch (DataIntegrityViolationException ex) {
                throw new DomainException(ProviderErrorCode.MEMBER_ALREADY_EXISTS);
            }
        }

        Map<String, Object> data = Map.of(
                "organizationId", orgId.toString(),
                "affectedUserIds", List.of(targetUserId));
        enqueueOutbox(orgId, EVT_MEMBER_CHANGED, data, now);
        return toMemberResponse(saved);
    }

    @Transactional
    public OrganizationMemberResponse updateMemberRole(UUID orgId, String targetUserId, OrgRole newRole) {
        if (newRole == OrgRole.ORG_OWNER) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN, "Cannot promote member to ORG_OWNER.");
        }
        OrgContext ctx =
                OrgContext.get();
        if (ctx != null && targetUserId.equals(ctx.userId())) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN, "Cannot modify your own organization role.");
        }
        OrganizationMemberEntity member = requireActiveModifiableMember(orgId, targetUserId);
        if (newRole == OrgRole.ORG_TECHNICIAN && member.getRole() != OrgRole.ORG_TECHNICIAN
                && memberRepository.existsByUserIdAndRoleAndStatus(
                        targetUserId, OrgRole.ORG_TECHNICIAN, EntityStatus.ACTIVE.name())) {
            throw new DomainException(ProviderErrorCode.MEMBER_ALREADY_EXISTS,
                    "User is already an active ORG_TECHNICIAN in another organization (1:1 limit in v0.1).");
        }
        member.setRole(newRole);
        return toMemberResponse(memberRepository.save(member));
    }

    @Transactional
    public OrganizationResponse requestLegalEntityUpgrade(
            UUID orgId, String userId, LegalEntityType targetType, String taxCode, String identityEvidenceRefs, String ifMatch) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (!org.getOwnerUserId().equals(userId)) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN, "Only ORG_OWNER can request a legal entity upgrade.");
        }
        if (org.getStatus() != OrganizationStatus.APPROVED || org.getLegalEntityType() != LegalEntityType.INDIVIDUAL) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "Only APPROVED INDIVIDUAL organizations can request a legal entity upgrade.");
        }
        if (targetType != LegalEntityType.HOUSEHOLD_BUSINESS && targetType != LegalEntityType.COMPANY) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Target legalEntityType must be HOUSEHOLD_BUSINESS or COMPANY.");
        }
        if (ifMatch != null && !ifMatch.isBlank()) {
            EtagSupport.verifyIfMatch(ifMatch, org.getVersion());
        }
        org.setPendingLegalEntityUpgrade(targetType);
        if (taxCode != null && !taxCode.isBlank()) {
            org.setTaxCode(taxCode.trim());
        }
        if (identityEvidenceRefs != null && !identityEvidenceRefs.isBlank()) {
            org.setIdentityEvidenceRefs(identityEvidenceRefs);
        }
        org.setUpdatedAt(Instant.now());
        return toOrgResponse(organizationRepository.save(org));
    }

    @Transactional
    public OrganizationResponse approveLegalEntityUpgrade(UUID orgId, String adminUserId) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));
        if (org.getPendingLegalEntityUpgrade() == null) {
            throw new DomainException(ProviderErrorCode.INVALID_ORGANIZATION_STATE,
                    "No pending legal entity upgrade on this organization.");
        }
        org.setLegalEntityType(org.getPendingLegalEntityUpgrade());
        org.setPendingLegalEntityUpgrade(null);
        org.setUpdatedAt(Instant.now());
        return toOrgResponse(organizationRepository.save(org));
    }

    @Transactional
    public void handleOwnerUserSuspended(String userId, String reason, String adminUserId) {
        List<OrganizationEntity> ownedApproved = organizationRepository.findByOwnerUserIdAndStatus(
                userId, OrganizationStatus.APPROVED);
        for (OrganizationEntity org : ownedApproved) {
            boolean isIndividual = org.getLegalEntityType() == LegalEntityType.INDIVIDUAL;
            boolean hasOtherAdmin = memberRepository.existsByOrganizationIdAndRoleAndStatusAndUserIdNot(
                    org.getId(), OrgRole.ORG_ADMIN, EntityStatus.ACTIVE.name(), userId);
            if (isIndividual || !hasOtherAdmin) {
                suspendOrganization(org.getId(), "Owner suspended: " + reason, null, false, adminUserId);
            }
        }
    }

    @Transactional(readOnly = true)
    public UUID findOrganizationIdIfUserIsMemberOfProvider(UUID providerId, String userId) {
        return providerRepository.findById(providerId)
                .map(ProviderEntity::getOrganizationId)
                .filter(orgId -> memberRepository.findByOrganizationIdAndUserId(orgId, userId)
                        .map(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                        .orElse(false))
                .orElse(null);
    }

    @Transactional
    public void removeMember(UUID orgId, String targetUserId) {
        OrganizationMemberEntity member = requireActiveModifiableMember(orgId, targetUserId);
        member.setStatus(EntityStatus.INACTIVE.name());
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
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
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
                .status(EntityStatus.PENDING.name())
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
                org.getLegalEntityType(),
                org.getStatus(),
                org.getRejectReason(),
                org.getResubmitCount(),
                org.isIdentityVerified(),
                org.getIdentityVerifiedAt(),
                org.getIdentityEvidenceRefs(),
                org.getPendingLegalEntityUpgrade(),
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
        return toMemberResponse(m, null);
    }

    private OrganizationMemberResponse toMemberResponse(
            OrganizationMemberEntity m, SupabaseRoleClient.ProfileSummary profile) {
        return new OrganizationMemberResponse(
                m.getId(),
                m.getOrganizationId(),
                m.getUserId(),
                profile != null ? profile.fullName() : null,
                profile != null ? profile.email() : null,
                m.getRole(),
                m.getStatus(),
                m.getJoinedAt());
    }
}
