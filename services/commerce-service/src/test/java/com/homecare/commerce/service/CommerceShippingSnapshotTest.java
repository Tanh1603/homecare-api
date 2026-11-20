package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.commerce.client.HouseholdAddressClient;
import com.homecare.commerce.client.HouseholdAddressClient.ResolvedShippingAddress;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.entity.InventoryEntity;
import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.entity.ProductEntity;
import com.homecare.commerce.exception.CommerceErrorCode;
import com.homecare.commerce.repository.InventoryRepository;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.commerce.repository.ProductRepository;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;

@SpringBootTest
@ActiveProfiles("test")
class CommerceShippingSnapshotTest {

    @Autowired
    private CommerceDomainService service;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private HouseholdAddressClient householdAddressClient;

    private UUID productId;

    @BeforeEach
    void seedProduct() {
        Instant now = Instant.now();
        UUID merchantId = UUID.randomUUID();
        merchantRepository.save(MerchantEntity.builder()
                .id(merchantId)
                .organizationId(UUID.randomUUID())
                .ownerProfileId("owner-" + merchantId)
                .name("Shop A")
                .city("Ho Chi Minh")
                .ratingAvg(5.0)
                .status(EntityStatus.ACTIVE.name())
                .createdAt(now)
                .build());
        productId = UUID.randomUUID();
        productRepository.save(ProductEntity.builder()
                .id(productId)
                .merchantId(merchantId)
                .sku("SKU-" + productId.toString().substring(0, 6))
                .name("Bo loc khong khi")
                .category("FILTER")
                .brand("HomeCare")
                .productType("PART")
                .unitPrice(200000L)
                .status(EntityStatus.ACTIVE.name())
                .createdAt(now)
                .build());
        inventoryRepository.save(InventoryEntity.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .availableQty(10)
                .reservedQty(0)
                .updatedAt(now)
                .build());
    }

    @Test
    void shippingAddressIdSavesSnapshotAndRejectsWhenUnavailableOrInvalid() {
        UUID addressId = UUID.randomUUID();
        when(householdAddressClient.resolveUserAddress(addressId, "buyer-1"))
                .thenReturn(new ResolvedShippingAddress(
                        addressId,
                        "Tran Thi B",
                        "0909998888",
                        "45 Nguyen Hue",
                        "26740",
                        "Phường Bến Nghé",
                        "79",
                        "Thành phố Hồ Chí Minh",
                        "45 Nguyen Hue, Phường Bến Nghé, Thành phố Hồ Chí Minh",
                        10.77,
                        106.70));

        OrderResponse order = service.createOrder("buyer-1", new CreateOrderRequest(
                List.of(new OrderLineRequest(productId, 1)),
                "Fake Client String",
                addressId));

        assertThat(order.shippingAddress()).isEqualTo("45 Nguyen Hue, Phường Bến Nghé, Thành phố Hồ Chí Minh");
        assertThat(order.shippingAddressSnapshot()).isNotNull();
        assertThat(order.shippingAddressSnapshot().wardCode()).isEqualTo("26740");
        assertThat(order.shippingAddressSnapshot().provinceCode()).isEqualTo("79");

        // Unavailable fail-closed
        long ordersBefore = orderRepository.count();
        when(householdAddressClient.resolveUserAddress(addressId, "buyer-1"))
                .thenThrow(new DomainException(CommerceErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.createOrder("buyer-1", new CreateOrderRequest(
                List.of(new OrderLineRequest(productId, 1)),
                "Should Not Fallback",
                addressId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommerceErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE.name());
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }

    @Test
    void legacyShippingAddressStringStillWorks() {
        OrderResponse order = service.createOrder("buyer-1", new CreateOrderRequest(
                List.of(new OrderLineRequest(productId, 1)),
                "Legacy 88 Dong Khoi"));

        assertThat(order.shippingAddress()).isEqualTo("Legacy 88 Dong Khoi");
        assertThat(order.shippingAddressSnapshot()).isNull();
    }
}
