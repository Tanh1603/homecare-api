package com.homecare.commerce.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.commerce.entity.InventoryEntity;
import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.entity.ProductEntity;
import com.homecare.commerce.repository.InventoryRepository;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.commerce.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CommerceDataInitializer implements CommandLineRunner {

    public static final UUID MERCHANT_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID MERCHANT_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID PRODUCT_AC_FILTER_ID = UUID.fromString("66666666-6666-6666-6666-666666666601");
    public static final UUID PRODUCT_RO_CORE_ID = UUID.fromString("66666666-6666-6666-6666-666666666605");
    public static final UUID PRODUCT_RO_MEMBRANE_ID = UUID.fromString("66666666-6666-6666-6666-666666666606");
    public static final UUID PRODUCT_ELCB_BREAKER_ID = UUID.fromString("66666666-6666-6666-6666-666666666610");
    public static final UUID PRODUCT_WRENCH_SCREW_KIT_ID = UUID.fromString("66666666-6666-6666-6666-666666666613");

    private final MerchantRepository merchantRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();

        MerchantEntity m1 = merchantRepository.save(MerchantEntity.builder()
                .id(MERCHANT_1_ID)
                .organizationId(MERCHANT_1_ID)
                .ownerProfileId("demo-organization")
                .name("Điện Lạnh Bách Khoa Sài Gòn")
                .city("TP. Hồ Chí Minh")
                .ratingAvg(4.9)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        MerchantEntity m2 = merchantRepository.save(MerchantEntity.builder()
                .id(MERCHANT_2_ID)
                .organizationId(MERCHANT_2_ID)
                .ownerProfileId("demo-organization")
                .name("HomeCare Pro Kỹ Thuật Số")
                .city("TP. Hồ Chí Minh")
                .ratingAvg(4.85)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        record SeedProduct(
                UUID id,
                UUID merchantId,
                String sku,
                String name,
                String cat,
                String brand,
                String productType,
                int warrantyMonths,
                int returnWindowDays,
                long price,
                int stock) {
        }

        List<SeedProduct> seeds = List.of(
                new SeedProduct(PRODUCT_AC_FILTER_ID, m1.getId(), "HC-AC-001", "Lưới lọc bụi kháng khuẩn PM2.5 Máy lạnh Daikin", "AC", "Daikin", "SMALL_PART", 0, 7, 185_000L, 50),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666602"), m1.getId(), "HC-AC-002", "Chai xịt vệ sinh bọt tuyết dàn lạnh 500ml", "AC", "3M Home", "SUPPLY", 0, 7, 95_000L, 120),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666603"), m1.getId(), "HC-AC-003", "Remote điều khiển Máy lạnh đa năng thông minh", "AC", "Panasonic", "MAJOR_PART", 6, 7, 145_000L, 80),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666604"), m1.getId(), "HC-WM-001", "Bộ 12 viên tẩy lồng máy giặt diệt khuẩn sinh học", "WASHER", "HomeCare Clean", "SUPPLY", 0, 7, 79_000L, 200),
                new SeedProduct(PRODUCT_RO_CORE_ID, m2.getId(), "HC-WP-001", "Bộ 3 lõi lọc thô 1-2-3 Máy lọc nước Karofi/Kangaroo", "WATER_PURIFIER", "Karofi", "SUPPLY", 0, 7, 220_000L, 90),
                new SeedProduct(PRODUCT_RO_MEMBRANE_ID, m2.getId(), "HC-WP-002", "Màng lọc RO DOW Filmtec 100GPD nhập khẩu Mỹ", "WATER_PURIFIER", "Filmtec", "MAJOR_PART", 12, 15, 480_000L, 40),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666607"), m1.getId(), "HC-WM-002", "Ống cấp nước máy giặt chống xoắn 2m chịu áp lực cao", "WASHER", "LG Parts", "SMALL_PART", 0, 15, 125_000L, 65),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666608"), m1.getId(), "HC-FR-001", "Bộ lọc khử mùi than hoạt tính Tủ lạnh Inverter", "FRIDGE", "Samsung", "SMALL_PART", 0, 7, 165_000L, 55),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666609"), m1.getId(), "HC-FR-002", "Ron cao su cửa tủ lạnh kháng khuẩn chống thoát nhiệt", "FRIDGE", "Toshiba", "MAJOR_PART", 6, 15, 290_000L, 30),
                new SeedProduct(PRODUCT_ELCB_BREAKER_ID, m2.getId(), "HC-EL-001", "Ổ cắm chống giật ELCB cho Bình nóng lạnh 20A", "ELECTRICAL", "Schneider", "MAJOR_PART", 12, 15, 320_000L, 45),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666611"), m2.getId(), "HC-EL-002", "Thanh Magie chống ăn mòn Bình nước nóng Ariston", "ELECTRICAL", "Ariston", "SMALL_PART", 0, 7, 150_000L, 70),
                new SeedProduct(UUID.fromString("66666666-6666-6666-6666-666666666612"), m1.getId(), "HC-AC-004", "Áo trùm vệ sinh máy lạnh vải chống thấm chuyên dụng", "AC", "HomeCare Pro", "TOOL", 0, 15, 210_000L, 60),
                new SeedProduct(PRODUCT_WRENCH_SCREW_KIT_ID, m1.getId(), "HC-TL-001", "Bộ ốc vít inox & cờ lê chuyên dụng lắp đặt điện lạnh", "AC", "Bosch Home", "TOOL", 0, 15, 135_000L, 80)
        );

        for (SeedProduct sp : seeds) {
            productRepository.save(ProductEntity.builder()
                    .id(sp.id())
                    .merchantId(sp.merchantId())
                    .sku(sp.sku())
                    .name(sp.name())
                    .category(sp.cat())
                    .brand(sp.brand())
                    .productType(sp.productType())
                    .warrantyMonths(sp.warrantyMonths())
                    .returnWindowDays(sp.returnWindowDays())
                    .description("Sản phẩm chính hãng " + sp.brand() + " phân phối bởi Cửa hàng đối tác HomeCare.")
                    .imageUrl("https://images.unsplash.com/photo-1581092160607-ee22621dd758?auto=format&fit=crop&w=600&q=80")
                    .unitPrice(sp.price())
                    .status("ACTIVE")
                    .createdAt(now)
                    .build());

            inventoryRepository.save(InventoryEntity.builder()
                    .id(UUID.randomUUID())
                    .productId(sp.id())
                    .availableQty(sp.stock())
                    .reservedQty(0)
                    .updatedAt(now)
                    .build());
        }
    }
}
