package com.homecare.provider.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ServiceOffering;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.ServiceOfferingRepository;
import com.homecare.provider.repository.TechnicianRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProviderDataInitializer implements CommandLineRunner {

    public static final UUID PROVIDER_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID PROVIDER_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID SERVICE_AC_CLEAN_ID = UUID.fromString("33333333-3333-3333-3333-333333333301");
    public static final UUID SERVICE_AC_REPAIR_ID = UUID.fromString("33333333-3333-3333-3333-333333333302");
    public static final UUID SERVICE_WASHER_CLEAN_ID = UUID.fromString("33333333-3333-3333-3333-333333333303");
    public static final UUID SERVICE_FRIDGE_REPAIR_ID = UUID.fromString("33333333-3333-3333-3333-333333333304");
    public static final UUID SERVICE_WATER_PURIFIER_ID = UUID.fromString("33333333-3333-3333-3333-333333333305");
    public static final UUID SERVICE_ELECTRICAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333306");
    public static final UUID DEMO_TECH_ID = UUID.fromString("44444444-4444-4444-4444-444444444401");

    private final ProviderRepository providerRepository;
    private final ServiceOfferingRepository offeringRepository;
    private final TechnicianRepository technicianRepository;
    private final ScheduleWindowRepository scheduleWindowRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (providerRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();

        ProviderEntity p1 = providerRepository.save(ProviderEntity.builder()
                .id(PROVIDER_1_ID)
                .organizationId(PROVIDER_1_ID)
                .ownerProfileId("demo-organization")
                .name("Điện Lạnh Bách Khoa Sài Gòn")
                .description("Chuyên bảo dưỡng, vệ sinh và sửa chữa máy lạnh, tủ lạnh, máy giặt chính hãng")
                .city("TP. Hồ Chí Minh")
                .phone("0901234567")
                .ratingAvg(4.9)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        ProviderEntity p2 = providerRepository.save(ProviderEntity.builder()
                .id(PROVIDER_2_ID)
                .organizationId(PROVIDER_2_ID)
                .ownerProfileId("demo-organization")
                .name("HomeCare Pro Kỹ Thuật Số")
                .description("Dịch vụ chăm sóc thiết bị gia dụng thông minh, lọc nước, điện dân dụng")
                .city("TP. Hồ Chí Minh")
                .phone("0909888777")
                .ratingAvg(4.8)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        offeringRepository.saveAll(List.of(
                ServiceOffering.builder()
                        .id(SERVICE_AC_CLEAN_ID)
                        .providerId(p1.getId())
                        .name("Vệ sinh & Bơm gas Máy lạnh treo tường")
                        .category("AC")
                        .description("Vệ sinh dàn nóng/lạnh, thông ống thoát nước, kiểm tra áp suất gas")
                        .basePrice(250_000L)
                        .estimatedDurationMinutes(60)
                        .active(true)
                        .createdAt(now)
                        .build(),
                ServiceOffering.builder()
                        .id(SERVICE_AC_REPAIR_ID)
                        .providerId(p1.getId())
                        .name("Chẩn đoán & Sửa chữa Máy lạnh Inverter")
                        .category("AC")
                        .description("Khắc phục lỗi bo mạch, rò gas, máy lạnh không lạnh")
                        .basePrice(450_000L)
                        .estimatedDurationMinutes(90)
                        .active(true)
                        .createdAt(now)
                        .build(),
                ServiceOffering.builder()
                        .id(SERVICE_WASHER_CLEAN_ID)
                        .providerId(p1.getId())
                        .name("Vệ sinh lồng giặt Máy giặt cửa ngang/cửa trên")
                        .category("WASHER")
                        .description("Tháo lồng giặt, khử khuẩn cặn bẩn và kiểm tra dây curoa")
                        .basePrice(350_000L)
                        .estimatedDurationMinutes(90)
                        .active(true)
                        .createdAt(now)
                        .build(),
                ServiceOffering.builder()
                        .id(SERVICE_FRIDGE_REPAIR_ID)
                        .providerId(p1.getId())
                        .name("Bảo dưỡng & Sửa Tủ lạnh Side-by-Side")
                        .category("FRIDGE")
                        .description("Kiểm tra block, cảm biến nhiệt độ, quạt dàn lạnh")
                        .basePrice(500_000L)
                        .estimatedDurationMinutes(90)
                        .active(true)
                        .createdAt(now)
                        .build(),
                ServiceOffering.builder()
                        .id(SERVICE_WATER_PURIFIER_ID)
                        .providerId(p2.getId())
                        .name("Thay lõi & Bảo dưỡng Máy lọc nước RO")
                        .category("WATER_PURIFIER")
                        .description("Đo TDS, sục rửa màng RO và kiểm tra bình áp")
                        .basePrice(200_000L)
                        .estimatedDurationMinutes(45)
                        .active(true)
                        .createdAt(now)
                        .build(),
                ServiceOffering.builder()
                        .id(SERVICE_ELECTRICAL_ID)
                        .providerId(p2.getId())
                        .name("Kiểm tra an toàn điện & bình nóng lạnh")
                        .category("ELECTRICAL")
                        .description("Chống giật ELCB, xả cặn bình nước nóng, đo dòng rò")
                        .basePrice(300_000L)
                        .estimatedDurationMinutes(60)
                        .active(true)
                        .createdAt(now)
                        .build()));

        Technician tech1 = technicianRepository.save(Technician.builder()
                .id(DEMO_TECH_ID)
                .profileId("demo-technician")
                .providerId(p1.getId())
                .fullName("Trần Văn Kỹ Thuật (Demo Technician)")
                .specialties("AC,WASHER,FRIDGE,WATER_PURIFIER,ELECTRICAL")
                .experienceYears(7)
                .ratingAvg(4.95)
                .activeAllocations(0)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        Technician tech2 = technicianRepository.save(Technician.builder()
                .id(UUID.fromString("44444444-4444-4444-4444-444444444402"))
                .profileId("tech-2-profile")
                .providerId(p1.getId())
                .fullName("Lê Hoàng Minh")
                .specialties("AC,FRIDGE")
                .experienceYears(4)
                .ratingAvg(4.7)
                .activeAllocations(1)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        Technician tech3 = technicianRepository.save(Technician.builder()
                .id(UUID.fromString("44444444-4444-4444-4444-444444444403"))
                .profileId("tech-3-profile")
                .providerId(p2.getId())
                .fullName("Nguyễn Quốc Bảo")
                .specialties("WATER_PURIFIER,ELECTRICAL,AC")
                .experienceYears(6)
                .ratingAvg(4.85)
                .activeAllocations(0)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        Instant windowStart = now.minus(1, ChronoUnit.DAYS);
        Instant windowEnd = now.plus(30, ChronoUnit.DAYS);
        for (Technician tech : List.of(tech1, tech2, tech3)) {
            scheduleWindowRepository.save(ScheduleWindow.builder()
                    .id(UUID.randomUUID())
                    .technicianId(tech.getId())
                    .startsAt(windowStart)
                    .endsAt(windowEnd)
                    .status("AVAILABLE")
                    .createdAt(now)
                    .build());
        }
    }
}
