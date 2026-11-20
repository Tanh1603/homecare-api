package com.homecare.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.common.error.DomainException;
import com.homecare.household.dto.AddressDtos.CreateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UpdateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UserAddressResponse;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.LocationRepository;
import com.homecare.household.repository.UserAddressRepository;
import com.homecare.household.service.AddressBookService;
import com.homecare.household.service.AdministrativeLocationImporter;
import com.homecare.household.service.HouseholdDomainService;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class AddressBookPostgresConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AdministrativeLocationImporter importer;

    @Autowired
    private AddressBookService addressBookService;

    @Autowired
    private HouseholdDomainService householdDomainService;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setupData() throws Exception {
        householdRepository.deleteAll();
        userAddressRepository.deleteAll();
        if (locationRepository.count() == 0) {
            importer.importFromClasspath(false);
        }
    }

    @Test
    void concurrentSetDefaultMaintainsSingleActiveDefaultAndSoftDeleteCleansHouseholdPointer() throws Exception {
        // Pick an active ward code from HCM ("79")
        String wardCode = jdbcTemplate.queryForObject(
                "SELECT w.official_code FROM locations w JOIN locations p ON w.parent_id = p.id "
                        + "WHERE p.official_code = '79' AND w.is_active = TRUE LIMIT 1",
                String.class);
        assertThat(wardCode).isNotBlank();

        UserAddressResponse addr1 = addressBookService.createAddress("conc-user",
                new CreateUserAddressRequest("Home", "An", "0901", "10 Hai Ba Trung", wardCode, 10.78, 106.69, true));

        // Concurrent first-default createAddress for a brand-new user returns DomainException(409) instead of HTTP 500
        ExecutorService firstPool = Executors.newFixedThreadPool(2);
        CountDownLatch firstReady = new CountDownLatch(2);
        CountDownLatch firstStart = new CountDownLatch(1);
        try {
            Callable<Boolean> c1 = () -> {
                firstReady.countDown();
                firstStart.await();
                try {
                    addressBookService.createAddress("brand-new-owner",
                            new CreateUserAddressRequest("Home", "B", "0902", "1 Le Loi", wardCode, null, null, true));
                    return true;
                } catch (DomainException ex) {
                    return false;
                }
            };
            Future<Boolean> r1 = firstPool.submit(c1);
            Future<Boolean> r2 = firstPool.submit(c1);
            firstReady.await();
            firstStart.countDown();
            int successes = (r1.get() ? 1 : 0) + (r2.get() ? 1 : 0);
            assertThat(successes).isGreaterThanOrEqualTo(1);
        } finally {
            firstPool.shutdownNow();
        }
        Integer brandNewDefaults = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_addresses WHERE owner_profile_id = 'brand-new-owner' "
                        + "AND is_default_shipping = TRUE AND is_active = TRUE",
                Integer.class);
        assertThat(brandNewDefaults).isEqualTo(1);
        UserAddressResponse addr2 = addressBookService.createAddress("conc-user",
                new CreateUserAddressRequest("Office", "An", "0901", "20 Le Loi", wardCode, 10.77, 106.70, false));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<UserAddressResponse> t1 = () -> {
                ready.countDown();
                start.await();
                return addressBookService.setDefaultAddress("conc-user", addr1.id());
            };
            Callable<UserAddressResponse> t2 = () -> {
                ready.countDown();
                start.await();
                return addressBookService.setDefaultAddress("conc-user", addr2.id());
            };
            Future<UserAddressResponse> f1 = pool.submit(t1);
            Future<UserAddressResponse> f2 = pool.submit(t2);
            ready.await();
            start.countDown();
            f1.get();
            f2.get();
        } finally {
            pool.shutdownNow();
        }

        Integer defaultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_addresses WHERE owner_profile_id = 'conc-user' "
                        + "AND is_default_shipping = TRUE AND is_active = TRUE",
                Integer.class);
        assertThat(defaultCount).isEqualTo(1);

        // Household lifecycle + update sync + soft delete cleanup on real Postgres
        HouseholdResponse h = householdDomainService.createHousehold("conc-user",
                new CreateHouseholdRequest("Nha chinh", null, null, addr1.id()));
        assertThatThrownBy(() -> householdDomainService.assignHouseholdServiceAddress("other-user", h.id(), addr1.id()))
                .isInstanceOf(DomainException.class);

        addressBookService.updateAddress("conc-user", addr1.id(),
                new UpdateUserAddressRequest(null, null, null, "99 Nguyen Trai", null, null, null));
        String expectedFormatted = addressBookService.getAddress("conc-user", addr1.id()).formattedAddress();
        assertThat(householdDomainService.getHousehold("conc-user", h.id()).address()).isEqualTo(expectedFormatted);

        addressBookService.deleteAddress("conc-user", addr1.id());
        HouseholdResponse afterDelete = householdDomainService.getHousehold("conc-user", h.id());
        assertThat(afterDelete.defaultServiceAddressId()).isNull();
        assertThat(afterDelete.address()).isEqualTo(expectedFormatted);
        List<UserAddressResponse> activeList = addressBookService.listAddresses("conc-user");
        assertThat(activeList).extracting(UserAddressResponse::id).containsExactly(addr2.id());
    }
}
