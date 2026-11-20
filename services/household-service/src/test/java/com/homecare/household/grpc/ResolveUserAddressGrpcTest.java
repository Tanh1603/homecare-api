package com.homecare.household.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import com.homecare.household.dto.AddressDtos.CreateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UserAddressResponse;
import com.homecare.household.entity.Location;
import com.homecare.household.entity.LocationLevel;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.LocationRepository;
import com.homecare.household.repository.UserAddressRepository;
import com.homecare.household.service.AddressBookService;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.ResolveUserAddressRequest;
import com.homecare.household.v1.ResolveUserAddressResponse;

import io.grpc.Metadata;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;

@SpringBootTest
@ActiveProfiles("test")
class ResolveUserAddressGrpcTest {

    @Autowired
    private HouseholdVerificationGrpcService grpcService;

    @Autowired
    private AddressBookService addressBookService;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private HomecareSecurityProperties securityProperties;

    @BeforeEach
    void setup() {
        householdRepository.deleteAll();
        userAddressRepository.deleteAll();
        locationRepository.deleteAllByLevel(LocationLevel.WARD);
        locationRepository.deleteAllByLevel(LocationLevel.PROVINCE);

        Instant now = Instant.now();
        Location province = locationRepository.save(Location.builder()
                .officialCode("79")
                .name("Thành phố Hồ Chí Minh")
                .level(LocationLevel.PROVINCE)
                .codename("ho_chi_minh")
                .divisionType("thành phố trung ương")
                .phoneCode(28)
                .sortOrder(1)
                .parentId(null)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        locationRepository.save(Location.builder()
                .officialCode("26734")
                .name("Phường Tân Định")
                .level(LocationLevel.WARD)
                .codename("phuong_tan_dinh")
                .divisionType("phường")
                .sortOrder(1)
                .parentId(province.getId())
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    void resolvesOwnedActiveAddressAndRejectsUnauthenticatedCrossOwnerOrInactive() throws Exception {
        UserAddressResponse addr = addressBookService.createAddress("user-a",
                new CreateUserAddressRequest("Home", "Nguyen Van A", "0901234567",
                        "10 Hai Ba Trung", "26734", 10.78, 106.69, true));

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(grpcService,
                        new WorkloadAuthServerInterceptor(securityProperties.getInternal().getWorkloadToken())))
                .build()
                .start();
        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        try {
            var unauthStub = HouseholdVerificationServiceGrpc.newBlockingStub(channel);
            assertThatThrownBy(() -> unauthStub.resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                    .setAddressId(addr.id().toString())
                    .setOwnerProfileId("user-a")
                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED);

            Metadata md = new Metadata();
            md.put(Metadata.Key.of("x-internal-workload-token", Metadata.ASCII_STRING_MARSHALLER),
                    securityProperties.getInternal().getWorkloadToken());
            var authStub = unauthStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

            ResolveUserAddressResponse resolved = authStub.resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                    .setAddressId(addr.id().toString())
                    .setOwnerProfileId("user-a")
                    .build());
            assertThat(resolved.getWardCode()).isEqualTo("26734");
            assertThat(resolved.getProvinceCode()).isEqualTo("79");
            assertThat(resolved.getFormattedAddress()).isEqualTo("10 Hai Ba Trung, Phường Tân Định, Thành phố Hồ Chí Minh");
            assertThat(resolved.getHasCoordinates()).isTrue();
            assertThat(resolved.getLatitude()).isEqualTo(10.78);

            // Cross-owner -> NOT_FOUND
            assertThatThrownBy(() -> authStub.resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                    .setAddressId(addr.id().toString())
                    .setOwnerProfileId("user-b")
                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND);

            // Inactive -> NOT_FOUND
            addressBookService.deleteAddress("user-a", addr.id());
            assertThatThrownBy(() -> authStub.resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                    .setAddressId(addr.id().toString())
                    .setOwnerProfileId("user-a")
                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND);

            // Invalid UUID -> INVALID_ARGUMENT
            assertThatThrownBy(() -> authStub.resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                    .setAddressId("not-a-uuid")
                    .setOwnerProfileId("user-a")
                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
