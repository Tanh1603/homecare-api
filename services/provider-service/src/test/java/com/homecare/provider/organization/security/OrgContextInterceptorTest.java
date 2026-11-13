package com.homecare.provider.organization.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import com.homecare.common.error.DomainException;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationEntity;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;

class OrgContextInterceptorTest {

    private OrganizationRepository organizationRepository;
    private OrganizationMemberRepository memberRepository;
    private OrgContextInterceptor interceptor;
    private HandlerMethod adminOnlyHandler;

    static class DummyController {
        @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN })
        public void adminAction() {
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        organizationRepository = mock(OrganizationRepository.class);
        memberRepository = mock(OrganizationMemberRepository.class);
        interceptor = new OrgContextInterceptor(organizationRepository, memberRepository);

        Method method = DummyController.class.getMethod("adminAction");
        adminOnlyHandler = new HandlerMethod(new DummyController(), method);

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user-123", null, "ROLE_ORGANIZATION"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        OrgContext.clear();
    }

    @Test
    void rejectsWhenHeaderMissingOrInvalidUuid() {
        MockHttpServletRequest reqMissing = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(reqMissing, res, adminOnlyHandler))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("MISSING_ORGANIZATION_HEADER");

        MockHttpServletRequest reqInvalid = new MockHttpServletRequest();
        reqInvalid.addHeader(OrgContextInterceptor.ORG_ID_HEADER, "not-a-uuid");
        assertThatThrownBy(() -> interceptor.preHandle(reqInvalid, res, adminOnlyHandler))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("MISSING_ORGANIZATION_HEADER");
    }

    @Test
    void rejectsWhenOrganizationNotApproved() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(OrganizationEntity.builder()
                .id(orgId)
                .name("Test Org")
                .type(OrganizationType.SERVICE)
                .status(OrganizationStatus.SUSPENDED)
                .build()));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(OrgContextInterceptor.ORG_ID_HEADER, orgId.toString());

        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), adminOnlyHandler))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORGANIZATION_NOT_APPROVED");
    }

    @Test
    void rejectsWhenUserNotMember() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(OrganizationEntity.builder()
                .id(orgId)
                .name("Test Org")
                .type(OrganizationType.SERVICE)
                .status(OrganizationStatus.APPROVED)
                .build()));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, "user-123")).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(OrgContextInterceptor.ORG_ID_HEADER, orgId.toString());

        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), adminOnlyHandler))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORG_MEMBERSHIP_REQUIRED");
    }

    @Test
    void rejectsWhenRoleInsufficient() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(OrganizationEntity.builder()
                .id(orgId)
                .name("Test Org")
                .type(OrganizationType.SERVICE)
                .status(OrganizationStatus.APPROVED)
                .build()));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, "user-123"))
                .thenReturn(Optional.of(OrganizationMemberEntity.builder()
                        .id(UUID.randomUUID())
                        .organizationId(orgId)
                        .userId("user-123")
                        .role(OrgRole.ORG_STAFF)
                        .status("ACTIVE")
                        .build()));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(OrgContextInterceptor.ORG_ID_HEADER, orgId.toString());

        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), adminOnlyHandler))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORG_ROLE_FORBIDDEN");
    }

    @Test
    void allowsValidMemberAndPopulatesOrgContext() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(OrganizationEntity.builder()
                .id(orgId)
                .name("Test Org")
                .type(OrganizationType.SERVICE)
                .status(OrganizationStatus.APPROVED)
                .build()));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, "user-123"))
                .thenReturn(Optional.of(OrganizationMemberEntity.builder()
                        .id(UUID.randomUUID())
                        .organizationId(orgId)
                        .userId("user-123")
                        .role(OrgRole.ORG_OWNER)
                        .status("ACTIVE")
                        .build()));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(OrgContextInterceptor.ORG_ID_HEADER, orgId.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, res, adminOnlyHandler);
        assertThat(allowed).isTrue();
        assertThat(OrgContext.get()).isNotNull();
        assertThat(OrgContext.get().organizationId()).isEqualTo(orgId);
        assertThat(OrgContext.get().userId()).isEqualTo("user-123");
        assertThat(OrgContext.get().role()).isEqualTo(OrgRole.ORG_OWNER);

        interceptor.afterCompletion(req, res, adminOnlyHandler, null);
        assertThat(OrgContext.get()).isNull();
    }
}
