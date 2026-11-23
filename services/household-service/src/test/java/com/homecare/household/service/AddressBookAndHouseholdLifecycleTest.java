package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.homecare.common.error.DomainException;
import com.homecare.household.dto.AddressDtos.CreateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UpdateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UserAddressResponse;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.entity.Location;
import com.homecare.household.entity.LocationLevel;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.LocationRepository;
import com.homecare.household.repository.UserAddressRepository;

@SpringBootTest
@ActiveProfiles("test")
class AddressBookAndHouseholdLifecycleTest {

    @Autowired
    private AddressBookService addressBookService;

    @Autowired
    private HouseholdDomainService householdService;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private Location province;
    private Location activeWard;
    private Location inactiveWard;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        householdRepository.deleteAll();
        userAddressRepository.deleteAll();
        locationRepository.deleteAllByLevel(LocationLevel.WARD);
        locationRepository.deleteAllByLevel(LocationLevel.PROVINCE);

        Instant now = Instant.now();
        province = locationRepository.save(Location.builder()
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
        activeWard = locationRepository.save(Location.builder()
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
        inactiveWard = locationRepository.save(Location.builder()
                .officialCode("26735")
                .name("Phường Cũ")
                .level(LocationLevel.WARD)
                .codename("phuong_cu")
                .divisionType("phường")
                .sortOrder(2)
                .parentId(province.getId())
                .active(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    void enforcesOwnershipActiveWardAndCoordinateValidation() throws Exception {
        // Location API returns only active wards
        assertThat(addressBookService.listWards("79", null))
                .extracting("officialCode")
                .containsExactly("26734");

        // Reject PROVINCE code or inactive WARD
        assertThatThrownBy(() -> addressBookService.createAddress("user-a",
                new CreateUserAddressRequest("Home", "An", "0901", "10 Hai Ba Trung", "79", null, null, true)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> addressBookService.createAddress("user-a",
                new CreateUserAddressRequest("Home", "An", "0901", "10 Hai Ba Trung", "26735", null, null, true)))
                .isInstanceOf(DomainException.class);

        // Reject partial GPS
        assertThatThrownBy(() -> addressBookService.createAddress("user-a",
                new CreateUserAddressRequest("Home", "An", "0901", "10 Hai Ba Trung", "26734", 10.7, null, true)))
                .isInstanceOf(DomainException.class);

        // Spoofing ownerProfileId in JSON does not assign to user-b
        mockMvc.perform(post("/api/v1/addresses")
                        .with(jwt().jwt(j -> j.subject("user-a").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerProfileId": "user-b",
                                  "label": "Home",
                                  "recipientName": "Nguyen Van A",
                                  "phoneNumber": "0901234567",
                                  "addressLine": "10 Hai Ba Trung",
                                  "locationCode": "26734",
                                  "defaultShipping": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formattedAddress")
                        .value("10 Hai Ba Trung, Phường Tân Định, Thành phố Hồ Chí Minh"));

        UUID userAAddressId = addressBookService.listAddresses("user-a").get(0).id();
        assertThat(addressBookService.listAddresses("user-b")).isEmpty();

        // Cross-owner read/update/default/delete rejected
        mockMvc.perform(get("/api/v1/addresses/" + userAAddressId)
                        .with(jwt().jwt(j -> j.subject("user-b").claim("role", "CUSTOMER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/addresses/" + userAAddressId + "/default")
                        .with(jwt().jwt(j -> j.subject("user-b").claim("role", "CUSTOMER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/addresses/" + userAAddressId)
                        .with(jwt().jwt(j -> j.subject("user-b").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void householdTriStateSyncAndSoftDeleteCleanup() throws Exception {
        UserAddressResponse addrA = addressBookService.createAddress("user-a",
                new CreateUserAddressRequest("Home", "An", "0901", "10 Hai Ba Trung", "26734", 10.78, 106.69, true));

        HouseholdResponse h1 = householdService.createHousehold("user-a",
                new CreateHouseholdRequest("Nha 1", null, null, addrA.id()));
        HouseholdResponse h2 = householdService.createHousehold("user-a",
                new CreateHouseholdRequest("Nha 2", "Legacy", null, null));
        householdService.assignHouseholdServiceAddress("user-a", h2.id(), addrA.id());

        // Cross-owner household assignment rejected
        assertThatThrownBy(() -> householdService.assignHouseholdServiceAddress("user-b", h2.id(), addrA.id()))
                .isInstanceOf(DomainException.class);

        // Update UserAddress syncs both linked households
        addressBookService.updateAddress("user-a", addrA.id(),
                new UpdateUserAddressRequest(null, null, null, "99 Dien Bien Phu", null, null, null));

        // Partial GPS merge & full GPS clear via HTTP JSON
        mockMvc.perform(put("/api/v1/addresses/" + addrA.id())
                        .with(jwt().jwt(j -> j.subject("user-a").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":10.80}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latitude").value(10.80))
                .andExpect(jsonPath("$.data.longitude").value(106.69));

        mockMvc.perform(put("/api/v1/addresses/" + addrA.id())
                        .with(jwt().jwt(j -> j.subject("user-a").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/addresses/" + addrA.id())
                        .with(jwt().jwt(j -> j.subject("user-a").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":null,\"longitude\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latitude").doesNotExist())
                .andExpect(jsonPath("$.data.longitude").doesNotExist());
        assertThat(householdService.getHousehold("user-a", h1.id()).address())
                .isEqualTo("99 Dien Bien Phu, Phường Tân Định, Thành phố Hồ Chí Minh");
        assertThat(householdService.getHousehold("user-a", h2.id()).address())
                .isEqualTo("99 Dien Bien Phu, Phường Tân Định, Thành phố Hồ Chí Minh");

        // Linked household ignores legacy address overwrite when defaultServiceAddressId is omitted
        mockMvc.perform(patch("/api/v1/households/" + h1.id())
                        .with(jwt().jwt(j -> j.subject("user-a").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Should Not Overwrite\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address")
                        .value("99 Dien Bien Phu, Phường Tân Định, Thành phố Hồ Chí Minh"))
                .andExpect(jsonPath("$.data.defaultServiceAddressId").value(addrA.id().toString()));

        // Soft-delete clears default + household pointers, keeps last formatted string
        addressBookService.deleteAddress("user-a", addrA.id());
        HouseholdResponse h1AfterDelete = householdService.getHousehold("user-a", h1.id());
        assertThat(h1AfterDelete.defaultServiceAddressId()).isNull();
        assertThat(h1AfterDelete.address()).isEqualTo("99 Dien Bien Phu, Phường Tân Định, Thành phố Hồ Chí Minh");

        // Explicit unlink on another linked household
        UserAddressResponse addrA2 = addressBookService.createAddress("user-a",
                new CreateUserAddressRequest("Office", "An", "0901", "1 Le Duan", "26734", null, null, true));
        householdService.assignHouseholdServiceAddress("user-a", h2.id(), addrA2.id());
        mockMvc.perform(patch("/api/v1/households/" + h2.id())
                        .with(jwt().jwt(j -> j.subject("user-a").claim("role", "CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultServiceAddressId\":null,\"address\":\"Manual Legacy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultServiceAddressId").doesNotExist())
                .andExpect(jsonPath("$.data.address").value("Manual Legacy"));
    }
}
