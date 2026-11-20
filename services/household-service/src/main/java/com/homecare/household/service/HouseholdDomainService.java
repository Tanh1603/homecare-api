package com.homecare.household.service;

import tools.jackson.databind.ObjectMapper;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.ActorContext;
import com.homecare.household.security.HouseholdAccessPolicy;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.CareRecordResponse;
import com.homecare.household.dto.HouseholdDtos.CreateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.CreateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.DeviceResponse;
import com.homecare.household.dto.HouseholdDtos.DeviceLifecycleResponse;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.dto.HouseholdDtos.MaintenanceScheduleResponse;
import com.homecare.household.dto.HouseholdDtos.UpdateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.RegisterSerialRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.entity.CareRecord;
import com.homecare.household.entity.Device;
import com.homecare.household.entity.Household;
import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.entity.MaintenanceSchedule;
import com.homecare.household.entity.MaintenancePolicyEntity;
import com.homecare.household.entity.UserAddress;
import com.homecare.household.entity.Warranty;
import com.homecare.household.entity.ManufacturerRegistrationStatus;
import com.homecare.household.exception.HouseholdErrorCode;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.DeviceRepository;
import com.homecare.household.repository.HouseholdOutboxEventRepository;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;
import com.homecare.household.repository.MaintenancePolicyRepository;
import com.homecare.household.repository.UserAddressRepository;
import com.homecare.household.repository.WarrantyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HouseholdDomainService {
    private final HouseholdRepository householdRepository;
    private final DeviceRepository deviceRepository;
    private final WarrantyRepository warrantyRepository;
    private final MaintenanceScheduleRepository scheduleRepository;
    private final MaintenancePolicyRepository maintenancePolicyRepository;
    private final CareRecordRepository careRecordRepository;
    private final HouseholdOutboxEventRepository outboxEventRepository;
    private final UserAddressRepository userAddressRepository;
    private final AddressBookService addressBookService;
    private final ProviderOrgClient providerOrgClient;
    private final HouseholdAccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    @Transactional
    public HouseholdResponse createHousehold(String ownerProfileId, CreateHouseholdRequest request) {
        if (request.defaultServiceAddressId() == null && (request.address() == null || request.address().isBlank())) {
            throw new DomainException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "Either defaultServiceAddressId or address must be provided.");
        }
        Instant now = Instant.now();
        Household household = Household.builder()
                .id(UUID.randomUUID())
                .ownerProfileId(ownerProfileId)
                .name(request.name().trim())
                .address(request.address() != null ? request.address().trim() : "")
                .timezone(request.timezone() == null || request.timezone().isBlank()
                        ? "Asia/Ho_Chi_Minh"
                        : request.timezone().trim())
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (request.defaultServiceAddressId() != null) {
            assignServiceAddress(household, request.defaultServiceAddressId(), ownerProfileId);
        }
        Household saved = householdRepository.save(household);
        return toHouseholdResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<HouseholdResponse> householdsPage(String ownerProfileId, int page, int size) {
        var pageable = pageRequest(page, size, "createdAt");
        var result = householdRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId, pageable);
        return PageResponse.of(result.map(this::toHouseholdResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public HouseholdResponse getHousehold(String ownerProfileId, UUID householdId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        return toHouseholdResponse(h);
    }

    @Transactional
    public HouseholdResponse updateHousehold(String ownerProfileId, UUID householdId, UpdateHouseholdRequest request) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        if (request.getName() != null && !request.getName().isBlank()) {
            h.setName(request.getName().trim());
        }
        if (request.isDefaultServiceAddressIdProvided()) {
            if (request.getDefaultServiceAddressId() != null) {
                assignServiceAddress(h, request.getDefaultServiceAddressId(), ownerProfileId);
            } else {
                h.setDefaultServiceAddressId(null);
            }
        }
        // Only allow legacy address String update when household is not linked to a UserAddress.
        if (h.getDefaultServiceAddressId() == null && request.getAddress() != null && !request.getAddress().isBlank()) {
            h.setAddress(request.getAddress().trim());
        }
        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            h.setTimezone(request.getTimezone().trim());
        }
        h.setUpdatedAt(Instant.now());
        return toHouseholdResponse(householdRepository.save(h));
    }

    @Transactional
    public HouseholdResponse assignHouseholdServiceAddress(String ownerProfileId, UUID householdId, UUID addressId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        assignServiceAddress(h, addressId, ownerProfileId);
        h.setUpdatedAt(Instant.now());
        return toHouseholdResponse(householdRepository.save(h));
    }

    private void assignServiceAddress(Household household, UUID addressId, String actorUserId) {
        if (!household.getOwnerProfileId().equals(actorUserId)) {
            throw new DomainException(HouseholdErrorCode.HOUSEHOLD_NOT_FOUND);
        }
        UserAddress address = userAddressRepository.lockByIdAndOwner(addressId, actorUserId)
                .filter(UserAddress::isActive)
                .orElseThrow(() -> new DomainException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Service address not found."));
        String formatted = addressBookService.toAddressResponse(address).formattedAddress();
        household.setDefaultServiceAddressId(address.getId());
        household.setAddress(formatted);
    }

    @Transactional
    public void deleteHousehold(String ownerProfileId, UUID householdId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        for (var device : deviceRepository.findByHouseholdIdOrderByCreatedAtDesc(householdId)) {
            deleteDevice(ownerProfileId, device.getId());
        }
        householdRepository.delete(h);
    }

    @Transactional
    public DeviceResponse createDevice(String ownerProfileId, CreateDeviceRequest request) {
        UUID householdId = request.householdId();
        if (householdId != null) {
            requireOwnedHousehold(ownerProfileId, householdId);
        } else {
            householdId = ensureDefaultHouseholdId(ownerProfileId);
        }
        Instant now = Instant.now();
        Device saved = deviceRepository.saveAndFlush(Device.builder()
                .id(UUID.randomUUID())
                .householdId(householdId)
                .ownerProfileId(ownerProfileId)
                .name(request.name().trim())
                .deviceType(request.deviceType().trim().toUpperCase())
                .brand(request.brand().trim())
                .model(request.model().trim())
                .serialNumber(request.serialNumber())
                .purchaseDate(request.purchaseDate())
                .status(EntityStatus.ACTIVE.name())
                .createdAt(now)
                .updatedAt(now)
                .build());
        createScheduleFromApprovedPolicy(saved.getId());
        return toDeviceResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> devicesPage(String ownerProfileId, UUID householdId, int page, int size) {
        var pageable = pageRequest(page, size, "createdAt");
        var result = householdId != null
                ? deviceRepository.findByHouseholdIdOrderByCreatedAtDesc(
                        requireOwnedHousehold(ownerProfileId, householdId).getId(), pageable)
                : deviceRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId, pageable);
        return PageResponse.of(result.map(this::toDeviceResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(String ownerProfileId, UUID deviceId) {
        return toDeviceResponse(requireOwnedDevice(ownerProfileId, deviceId));
    }

    @Transactional
    public DeviceResponse updateDevice(
            String ownerProfileId,
            UUID deviceId,
            String ifMatchHeader,
            UpdateDeviceRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        EtagSupport.verifyIfMatch(ifMatchHeader, device.getVersion());
        if (request.name() != null && !request.name().isBlank()) {
            device.setName(request.name().trim());
        }
        if (request.deviceType() != null && !request.deviceType().isBlank()) {
            device.setDeviceType(request.deviceType().trim().toUpperCase());
        }
        if (request.brand() != null && !request.brand().isBlank()) {
            device.setBrand(request.brand().trim());
        }
        if (request.model() != null && !request.model().isBlank()) {
            device.setModel(request.model().trim());
        }
        if (request.serialNumber() != null) {
            device.setSerialNumber(request.serialNumber().trim());
        }
        if (request.purchaseDate() != null) {
            device.setPurchaseDate(request.purchaseDate());
        }
        if (request.status() != null && !request.status().isBlank()) {
            device.setStatus(request.status().trim().toUpperCase());
        }
        device.setUpdatedAt(Instant.now());
        return toDeviceResponse(deviceRepository.saveAndFlush(device));
    }

    @Transactional
    public void deleteDevice(String ownerProfileId, UUID deviceId) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        if (warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream()
                .anyMatch(w -> "ORDER_AUTO_ACTIVATED".equalsIgnoreCase(w.getSource()))) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "Cannot delete a device with an electronic store warranty.");
        }
        careRecordRepository.deleteByDeviceId(deviceId);
        scheduleRepository.deleteByDeviceId(deviceId);
        warrantyRepository.deleteByDeviceId(deviceId);
        deviceRepository.delete(device);
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> warrantiesPage(ActorContext actor, UUID requestedOrgId, int page, int size) {
        var pageable = pageRequest(page, size, "endsOn");
        UUID orgScope = accessPolicy.resolveWarrantyOrganizationScope(actor, requestedOrgId);
        var result = orgScope != null
                ? warrantyRepository.search(null, orgScope, pageable)
                : warrantyRepository.search(actor.isGlobalAdmin() ? null : actor.userId(), null, pageable);
        return PageResponse.of(result.map(this::toWarrantyResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> warrantiesPage(String user, UUID org, boolean admin, boolean operator, int page, int size) {
        var pageable = pageRequest(page, size, "endsOn");
        if (org != null && !admin && (!operator || !providerOrgClient.verifyOrganizationMembership(org, user))) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        var result = org != null
                ? warrantyRepository.search(null, org, pageable)
                : warrantyRepository.search(admin ? null : user, null, pageable);
        return PageResponse.of(result.map(this::toWarrantyResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenanceScheduleResponse> schedulesPage(String user, int page, int size) {
        var result = scheduleRepository.findByOwnerProfileId(user, pageRequest(page, size, "dueOn"));
        return PageResponse.of(result.map(this::toScheduleResponse).getContent(), page, size, result.getTotalElements());
    }

    private Pageable pageRequest(int page, int size, String field) {
        PageResponse.validate(page, size);
        return PageRequest.of(page - 1, size, Sort.by(field, "id"));
    }

    @Transactional
    public WarrantyResponse createWarranty(String ownerProfileId, UUID deviceId, CreateWarrantyRequest request) {
        return createWarranty(ownerProfileId, null, deviceId, request);
    }

    public WarrantyResponse createWarranty(
            String ownerProfileId, String ownerName, UUID deviceId, CreateWarrantyRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        if (request.endsOn().isBefore(request.startsOn())) {
            throw new DomainException(
                    HouseholdErrorCode.DATE_ORDER_INVALID,
                    "Warranty endsOn must not be before startsOn.");
        }
        Warranty saved = warrantyRepository.save(Warranty.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .ownerName(ownerName)
                .organizationId(null)
                .source("MANUAL")
                .providerName(request.providerName().trim())
                .startsOn(request.startsOn())
                .endsOn(request.endsOn())
                .notes(request.notes())
                .documentUrl(request.documentUrl())
                .status(request.endsOn().isBefore(LocalDate.now()) ? "EXPIRED" : EntityStatus.ACTIVE.name())
                .createdAt(Instant.now())
                .build());
        return toWarrantyResponse(saved);
    }

    @Transactional
    public WarrantyResponse autoActivateWarrantyFromOrderLineDelivered(
            String buyerProfileId,
            UUID organizationId,
            String merchantName,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String category,
            String brand,
            String productType,
            int warrantyMonths) {
        return autoActivateWarrantyFromOrderLineDelivered(
                buyerProfileId, null, organizationId, merchantName, orderId, orderLineId,
                productId, productName, category, brand, productType, warrantyMonths);
    }

    @Transactional
    public WarrantyResponse autoActivateWarrantyFromOrderLineDelivered(
            String buyerProfileId,
            String buyerName,
            UUID organizationId,
            String merchantName,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String category,
            String brand,
            String productType,
            int warrantyMonths) {
        List<WarrantyResponse> created = autoActivateFromOrderLineDelivered(
                buyerProfileId,
                buyerName,
                organizationId,
                merchantName,
                orderId,
                orderLineId,
                productId,
                productName,
                category,
                brand,
                productType,
                null,
                null,
                "DELIVERY_DATE",
                warrantyMonths,
                1,
                List.of(),
                null);
        return created.isEmpty() ? null : created.getFirst();
    }

    @Transactional
    public List<WarrantyResponse> autoActivateFromOrderLineDelivered(
            String buyerProfileId,
            String buyerName,
            UUID organizationId,
            String merchantName,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String category,
            String brand,
            String productType,
            String model,
            String deviceEligibility,
            String warrantyStartTrigger,
            int warrantyMonths,
            int quantityDelivered,
            List<String> unitKeys,
            String shippingAddressFallback) {
        boolean createsDevice = "REQUIRED".equalsIgnoreCase(deviceEligibility)
                || "OPTIONAL".equalsIgnoreCase(deviceEligibility);
        if (!createsDevice && warrantyMonths <= 0) {
            return List.of();
        }

        String resolvedModel = (model != null && !model.isBlank()) ? model.trim() : null;
        String trigger = (warrantyStartTrigger == null || warrantyStartTrigger.isBlank())
                ? "DELIVERY_DATE"
                : warrantyStartTrigger.trim().toUpperCase();
        boolean activateNow = "DELIVERY_DATE".equals(trigger) || "PURCHASE".equals(trigger);

        List<String> effectiveUnitKeys;
        if (createsDevice) {
            List<String> normalizedKeys = unitKeys == null ? List.of()
                    : unitKeys.stream()
                            .filter(k -> k != null && !k.isBlank())
                            .map(String::trim)
                            .distinct()
                            .toList();
            if (!normalizedKeys.isEmpty()) {
                effectiveUnitKeys = normalizedKeys;
            } else {
                int units = Math.max(1, quantityDelivered);
                effectiveUnitKeys = IntStream.rangeClosed(1, units)
                        .mapToObj(i -> orderLineId + ":" + i)
                        .toList();
            }
        } else {
            effectiveUnitKeys = List.of(orderLineId + ":orderline");
        }

        UUID householdId = createsDevice ? ensureDefaultHouseholdId(buyerProfileId, shippingAddressFallback) : null;
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        List<WarrantyResponse> results = new ArrayList<>();

        for (String unitKey : effectiveUnitKeys) {
            Optional<Warranty> existingWarranty = warrantyRepository.findByUnitKey(unitKey);
            if (existingWarranty.isEmpty() && effectiveUnitKeys.size() == 1 && (unitKeys == null || unitKeys.isEmpty())) {
                existingWarranty = warrantyRepository.findFirstByOrderLineIdOrderByCreatedAtAsc(orderLineId);
            }
            if (existingWarranty.isPresent()) {
                results.add(toWarrantyResponse(existingWarranty.get()));
                continue;
            }

            Device unitDevice = null;
            if (createsDevice) {
                unitDevice = deviceRepository.findByUnitKey(unitKey).orElseGet(() -> deviceRepository.saveAndFlush(
                        Device.builder()
                                .id(UUID.randomUUID())
                                .householdId(householdId)
                                .ownerProfileId(buyerProfileId)
                                .name(productName != null && !productName.isBlank() ? productName.trim()
                                        : "Thiết bị mua tại HomeCare")
                                .deviceType(category != null && !category.isBlank() ? category.trim().toUpperCase() : "AC")
                                .brand(brand != null && !brand.isBlank() ? brand.trim() : "HomeCare")
                                .model(resolvedModel)
                                .unitKey(unitKey)
                                .purchaseDate(today)
                                .status(EntityStatus.ACTIVE.name())
                                .createdAt(now)
                                .updatedAt(now)
                                .build()));
            }

            if (warrantyMonths > 0) {
                LocalDate startsOn = activateNow ? today : null;
                LocalDate endsOn = activateNow ? today.plusMonths(warrantyMonths) : null;
                String status = activateNow ? EntityStatus.ACTIVE.name() : "PENDING_TRIGGER";
                Warranty savedWarranty = warrantyRepository.save(Warranty.builder()
                        .id(UUID.randomUUID())
                        .deviceId(unitDevice != null ? unitDevice.getId() : null)
                        .ownerProfileId(buyerProfileId)
                        .ownerName(buyerName)
                        .organizationId(organizationId)
                        .warrantyResponsibleOrgId(organizationId)
                        .orderId(orderId)
                        .orderLineId(orderLineId)
                        .productId(productId)
                        .unitKey(unitKey)
                        .startTrigger(trigger)
                        .coverageMonths(warrantyMonths)
                        .manufacturerRegistrationStatus(ManufacturerRegistrationStatus.NOT_VERIFIED)
                        .source("ORDER_AUTO_ACTIVATED")
                        .providerName(merchantName != null && !merchantName.isBlank()
                                ? merchantName.trim()
                                : "Cửa hàng HomeCare")
                        .startsOn(startsOn)
                        .endsOn(endsOn)
                        .notes("Bảo hành điện tử tự động kích hoạt (" + warrantyMonths + " tháng) từ đơn hàng #" + orderId)
                        .status(status)
                        .createdAt(now)
                        .build());
                results.add(toWarrantyResponse(savedWarranty));
            }
        }
        return results;
    }

    @Transactional
    public void recordInstallationCompleted(
            UUID bookingId,
            UUID deviceId,
            UUID orderLineId,
            UUID executorOrgId,
            Instant completedAt,
            List<String> evidenceRefs) {
        if (bookingId == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Installation completion requires bookingId.");
        }
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Installation completion requires evidence.");
        }
        List<Warranty> warranties = orderLineId == null
                ? new ArrayList<>()
                : new ArrayList<>(warrantyRepository.findAllByOrderLineIdForUpdate(orderLineId));
        if (deviceId != null) {
            warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream()
                    .filter(w -> !warranties.contains(w))
                    .forEach(warranties::add);
        }
        for (Warranty warranty : warranties) {
            if (deviceId != null && warranty.getDeviceId() != null
                    && !deviceId.equals(warranty.getDeviceId())) {
                continue;
            }
            Device device = warranty.getDeviceId() == null ? null
                    : deviceRepository.findById(warranty.getDeviceId()).orElse(null);
            if (device == null && deviceId != null) {
                device = deviceRepository.findById(deviceId).orElse(null);
                if (device != null) {
                    warranty.setDeviceId(device.getId());
                }
            }
            boolean firstInstallation = device != null && !"COMPLETED".equalsIgnoreCase(device.getInstallationStatus());
            if (device != null && firstInstallation) {
                device.setInstallationStatus("COMPLETED");
                device.setInstallationCompletedAt(completedAt != null ? completedAt : Instant.now());
                device.setInstallationBookingId(bookingId);
                device.setInstallationEvidenceRefs(evidenceRefs == null ? null : String.join(",", evidenceRefs));
                deviceRepository.save(device);
            }
            if (!"INSTALLATION_COMPLETED".equalsIgnoreCase(warranty.getStartTrigger())) {
                continue;
            }
            if (device == null || device.getSerialNumber() == null || device.getSerialNumber().isBlank()) {
                warranty.setStatus("PENDING_SERIAL");
            } else if (!"ACTIVE".equalsIgnoreCase(warranty.getStatus())) {
                activateInstallationWarranty(warranty);
            }
            if (!"ACTIVE".equalsIgnoreCase(warranty.getStatus())) {
                warranty.setWarrantyResponsibleOrgId(executorOrgId != null ? executorOrgId : warranty.getWarrantyResponsibleOrgId());
                warranty.setTriggerEvidenceRef(String.join(",", evidenceRefs));
                warrantyRepository.save(warranty);
            }
            if (device != null && careRecordRepository.findFirstByDeviceIdAndKindOrderByCreatedAtAsc(
                    device.getId(), "INSTALLATION").isEmpty()) {
                careRecordRepository.save(CareRecord.builder()
                        .id(UUID.randomUUID())
                        .deviceId(device.getId())
                        .ownerProfileId(device.getOwnerProfileId())
                        .kind("INSTALLATION")
                        .occurredAt(completedAt != null ? completedAt : Instant.now())
                        .title("Installation completed")
                        .notes(evidenceRefs == null ? null : String.join(",", evidenceRefs))
                        .bookingId(bookingId)
                        .createdAt(Instant.now())
                        .build());
            }
        }
    }

    private void activateInstallationWarranty(Warranty warranty) {
        LocalDate startsOn = LocalDate.now();
        warranty.setStartsOn(startsOn);
        warranty.setEndsOn(startsOn.plusMonths(warranty.getCoverageMonths() == null ? 0 : warranty.getCoverageMonths()));
        warranty.setStatus(EntityStatus.ACTIVE.name());
    }

    @Transactional
    public DeviceResponse registerSerial(String ownerProfileId, UUID deviceId, RegisterSerialRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        device.setSerialNumber(request.serialNumber().trim());
        device.setUpdatedAt(Instant.now());
        Device saved = deviceRepository.saveAndFlush(device);
        warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream()
                .filter(w -> "PENDING_SERIAL".equalsIgnoreCase(w.getStatus()))
                .forEach(w -> {
                    activateInstallationWarranty(w);
                    warrantyRepository.save(w);
                });
        return toDeviceResponse(saved);
    }

    @Transactional(readOnly = true)
    public DeviceLifecycleResponse getDeviceLifecycle(String ownerProfileId, UUID deviceId) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        Warranty warranty = warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream().findFirst().orElse(null);
        return new DeviceLifecycleResponse(
                device.getId(), device.getHouseholdId(), device.getOwnerProfileId(), device.getName(),
                device.getDeviceType(), device.getBrand(), device.getModel(), device.getSerialNumber(),
                device.getInstallationStatus(), device.getInstallationCompletedAt(), device.getInstallationBookingId(),
                warranty == null ? null : warranty.getOrderId(), warranty == null ? null : warranty.getOrderLineId(),
                warranty == null ? null : warranty.getStatus(), warranty == null ? null : warranty.getSource(),
                warranty == null || warranty.getManufacturerRegistrationStatus() == null
                        ? null : warranty.getManufacturerRegistrationStatus().name(),
                warranty == null ? null : warranty.getTriggerEvidenceRef(),
                scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId).stream().map(this::toScheduleResponse).toList(),
                careRecordRepository.findByDeviceIdOrderByOccurredAtDesc(deviceId).stream().map(this::toCareRecordResponse).toList(),
                List.of());
    }

    @Transactional
    public CareRecordResponse recordCareHistoryFromBookingCompleted(
            UUID bookingId,
            UUID deviceId,
            String requesterProfileId,
            String bookingType,
            String serviceName,
            String notes) {
        if (bookingId == null || deviceId == null) {
            return null;
        }
        Optional<Device> deviceOpt = deviceRepository.findLockedById(deviceId);
        if (deviceOpt.isEmpty()) {
            return null;
        }
        try {
            Optional<CareRecord> existing = careRecordRepository.findByBookingId(bookingId);
            if (existing.isPresent()) {
                return toCareRecordResponse(existing.get());
            }
            Device device = deviceOpt.get();
            String kind = "WARRANTY_CLAIM".equalsIgnoreCase(bookingType) ? "WARRANTY_REPAIR"
                    : ("MAINTENANCE".equalsIgnoreCase(bookingType)
                            || "MAINTENANCE_SERVICE".equalsIgnoreCase(bookingType)
                                    ? "MAINTENANCE" : "PAID_REPAIR");
            Instant now = Instant.now();
            CareRecord saved = careRecordRepository.saveAndFlush(CareRecord.builder()
                    .id(UUID.randomUUID())
                    .deviceId(device.getId())
                    .ownerProfileId(requesterProfileId != null ? requesterProfileId : device.getOwnerProfileId())
                    .kind(kind)
                    .occurredAt(now)
                    .title(serviceName != null && !serviceName.isBlank() ? serviceName : kind)
                    .notes(notes)
                    .bookingId(bookingId)
                    .createdAt(now)
                    .build());
            if ("MAINTENANCE".equals(kind)) {
                advanceMaintenanceSchedule(deviceId, now);
            }
            return toCareRecordResponse(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            return careRecordRepository.findByBookingId(bookingId)
                    .map(this::toCareRecordResponse)
                    .orElseThrow(() -> duplicate);
        }
    }

    private void advanceMaintenanceSchedule(UUID deviceId, Instant servicedAt) {
        scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId).stream()
                .filter(s -> !"CANCELLED".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .ifPresent(schedule -> {
                    int cadence = schedule.getCadenceMonths() == null ? 6 : schedule.getCadenceMonths();
                    schedule.setDueOn(schedule.getDueOn().plusMonths(cadence));
                    schedule.setStatus("SCHEDULED");
                    schedule.setNotifiedAt(null);
                    schedule.setCompletedAt(servicedAt);
                    schedule.setLastServicedAt(servicedAt);
                    scheduleRepository.save(schedule);
                });
    }

    @Transactional
    public MaintenanceScheduleResponse createScheduleFromApprovedPolicy(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return null;
        }
        Optional<MaintenancePolicyEntity> policy = maintenancePolicyRepository
                .findFirstByCategoryAndModelAndStatusAndConsentGrantedTrueOrderByPolicyVersionDesc(
                        device.getDeviceType(), device.getModel(), "APPROVED");
        if (policy.isEmpty()) {
            return null;
        }
        MaintenancePolicyEntity p = policy.get();
        if (p.getDeviceId() != null && !p.getDeviceId().equals(deviceId)) {
            return null;
        }
        if (scheduleRepository.existsByDeviceIdAndPolicyVersion(deviceId, p.getPolicyVersion())) {
            return scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId).stream()
                    .filter(s -> java.util.Objects.equals(s.getPolicyVersion(), p.getPolicyVersion()))
                    .findFirst().map(this::toScheduleResponse).orElse(null);
        }
        MaintenanceSchedule saved = scheduleRepository.save(MaintenanceSchedule.builder()
                .id(UUID.randomUUID())
                .deviceId(deviceId)
                .ownerProfileId(device.getOwnerProfileId())
                .title(p.getTitle())
                .cadenceMonths(p.getCadenceMonths())
                .policyVersion(p.getPolicyVersion())
                .dueOn(p.getFirstDueOn() == null
                        ? LocalDate.now().plusMonths(p.getCadenceMonths())
                        : p.getFirstDueOn())
                .status("SCHEDULED")
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional
    public MaintenancePolicyEntity createMaintenancePolicy(MaintenancePolicyEntity policy) {
        MaintenancePolicyEntity saved = maintenancePolicyRepository.saveAndFlush(policy);
        deviceRepository.findAll().stream()
                .filter(d -> d.getDeviceType().equalsIgnoreCase(saved.getCategory()))
                .filter(d -> d.getModel().equalsIgnoreCase(saved.getModel()))
                .filter(d -> saved.getDeviceId() == null || saved.getDeviceId().equals(d.getId()))
                .forEach(d -> createScheduleFromApprovedPolicy(d.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> listWarrantiesPage(String ownerProfileId, UUID deviceId, int page, int size) {
        return slicePage(listWarranties(ownerProfileId, deviceId), page, size);
    }

    @Transactional(readOnly = true)
    public List<WarrantyResponse> listWarranties(String ownerProfileId, UUID deviceId) {
        requireOwnedDevice(ownerProfileId, deviceId);
        return warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId)
                .stream()
                .map(this::toWarrantyResponse)
                .toList();
    }

    @Transactional
    public WarrantyResponse updateWarranty(String ownerProfileId, UUID warrantyId, UpdateWarrantyRequest request) {
        Warranty w = requireMutableOwnedWarranty(ownerProfileId, warrantyId);
        LocalDate newStarts = request.startsOn() != null ? request.startsOn() : w.getStartsOn();
        LocalDate newEnds = request.endsOn() != null ? request.endsOn() : w.getEndsOn();
        if (newEnds.isBefore(newStarts)) {
            throw new DomainException(
                    HouseholdErrorCode.DATE_ORDER_INVALID,
                    "Warranty endsOn must not be before startsOn.");
        }
        w.setStartsOn(newStarts);
        w.setEndsOn(newEnds);
        if (request.providerName() != null && !request.providerName().isBlank()) {
            w.setProviderName(request.providerName().trim());
        }
        if (request.notes() != null) {
            w.setNotes(request.notes());
        }
        if (request.documentUrl() != null) {
            w.setDocumentUrl(request.documentUrl());
        }
        if (request.status() != null && !request.status().isBlank()) {
            w.setStatus(request.status().trim().toUpperCase());
        }
        return toWarrantyResponse(warrantyRepository.save(w));
    }

    @Transactional
    public void deleteWarranty(String ownerProfileId, UUID warrantyId) {
        Warranty w = requireMutableOwnedWarranty(ownerProfileId, warrantyId);
        warrantyRepository.delete(w);
    }

    private Warranty requireMutableOwnedWarranty(String ownerProfileId, UUID warrantyId) {
        Warranty w = warrantyRepository.findById(warrantyId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.WARRANTY_NOT_FOUND));
        if (!w.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this warranty.");
        }
        if ("ORDER_AUTO_ACTIVATED".equalsIgnoreCase(w.getSource())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Store-issued warranties cannot be modified or deleted.");
        }
        return w;
    }

    @Transactional
    public MaintenanceScheduleResponse createSchedule(
            String ownerProfileId,
            UUID deviceId,
            CreateMaintenanceScheduleRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        MaintenanceSchedule saved = scheduleRepository.save(MaintenanceSchedule.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .title(request.title().trim())
                .cadenceMonths(request.cadenceMonths() == null ? 6 : request.cadenceMonths())
                .dueOn(request.dueOn())
                .status("SCHEDULED")
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenanceScheduleResponse> listSchedulesPage(String ownerProfileId, UUID deviceId, int page, int size) {
        return slicePage(listSchedules(ownerProfileId, deviceId), page, size);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceScheduleResponse> listSchedules(String ownerProfileId, UUID deviceId) {
        requireOwnedDevice(ownerProfileId, deviceId);
        return scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId)
                .stream()
                .map(this::toScheduleResponse)
                .toList();
    }

    @Transactional
    public MaintenanceScheduleResponse updateSchedule(
            String ownerProfileId,
            UUID scheduleId,
            UpdateMaintenanceScheduleRequest request) {
        MaintenanceSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.SCHEDULE_NOT_FOUND));
        if (!s.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this schedule.");
        }
        if (request.dueOn() != null
                && s.getNotifiedAt() != null
                && !"COMPLETED".equals(request.status())) {
            throw new DomainException(
                    HouseholdErrorCode.SCHEDULE_ALREADY_NOTIFIED,
                    "Schedule has already triggered a notification; complete or create a new schedule.");
        }
        if (request.title() != null && !request.title().isBlank()) {
            s.setTitle(request.title().trim());
        }
        if (request.cadenceMonths() != null) {
            s.setCadenceMonths(request.cadenceMonths());
        }
        if (request.dueOn() != null) {
            s.setDueOn(request.dueOn());
        }
        if (request.status() != null && !request.status().isBlank()) {
            String nextStatus = request.status().trim().toUpperCase();
            s.setStatus(nextStatus);
            if ("COMPLETED".equals(nextStatus)) {
                s.setCompletedAt(Instant.now());
            }
        }
        return toScheduleResponse(scheduleRepository.save(s));
    }

    @Transactional
    public void deleteSchedule(String ownerProfileId, UUID scheduleId) {
        MaintenanceSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.SCHEDULE_NOT_FOUND));
        if (!s.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this schedule.");
        }
        scheduleRepository.delete(s);
    }

    @Transactional
    public CareRecordResponse createCareRecord(
            String ownerProfileId,
            UUID deviceId,
            CreateCareRecordRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();
        CareRecord saved = careRecordRepository.save(CareRecord.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .kind(request.kind().trim().toUpperCase())
                .occurredAt(occurredAt)
                .title(request.title() != null ? request.title().trim() : request.kind().trim())
                .notes(request.notes())
                .bookingId(request.bookingId())
                .createdAt(Instant.now())
                .build());
        return toCareRecordResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<CareRecordResponse> listCareRecordsPage(String ownerProfileId, UUID deviceId, int page, int size) {
        return slicePage(listCareRecords(ownerProfileId, deviceId), page, size);
    }

    private static <T> PageResponse<T> slicePage(List<T> all, int page, int size) {
        PageResponse.validate(page, size);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return PageResponse.of(all.subList(from, to), page, size, all.size());
    }

    @Transactional(readOnly = true)
    public List<CareRecordResponse> listCareRecords(String ownerProfileId, UUID deviceId) {
        requireOwnedDevice(ownerProfileId, deviceId);
        return careRecordRepository.findByDeviceIdOrderByOccurredAtDesc(deviceId)
                .stream()
                .map(this::toCareRecordResponse)
                .toList();
    }

    @Transactional
    public CareRecordResponse updateCareRecord(
            String ownerProfileId,
            UUID recordId,
            UpdateCareRecordRequest request) {
        CareRecord r = careRecordRepository.findById(recordId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.CARE_RECORD_NOT_FOUND));
        if (!r.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this care record.");
        }
        if ("INSTALLATION".equalsIgnoreCase(r.getKind())) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "Installation history is immutable.");
        }
        if (request.kind() != null && !request.kind().isBlank()) {
            r.setKind(request.kind().trim().toUpperCase());
        }
        if (request.occurredAt() != null) {
            r.setOccurredAt(request.occurredAt());
        }
        if (request.title() != null) {
            r.setTitle(request.title().trim());
        }
        if (request.notes() != null) {
            r.setNotes(request.notes());
        }
        return toCareRecordResponse(careRecordRepository.save(r));
    }

    @Transactional
    public void deleteCareRecord(String ownerProfileId, UUID recordId) {
        CareRecord r = careRecordRepository.findById(recordId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.CARE_RECORD_NOT_FOUND));
        if (!r.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this care record.");
        }
        if ("INSTALLATION".equalsIgnoreCase(r.getKind())) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "Installation history is immutable.");
        }
        careRecordRepository.delete(r);
    }

    @Transactional(readOnly = true)
    public VerifyDeviceReferenceResponse verifyDeviceReference(UUID deviceId, String requesterProfileId) {
        return deviceRepository.findById(deviceId)
                .filter(d -> d.getOwnerProfileId().equals(requesterProfileId) && EntityStatus.ACTIVE.name().equals(d.getStatus()))
                .map(d -> new VerifyDeviceReferenceResponse(
                        true,
                        d.getId(),
                        d.getHouseholdId(),
                        d.getDeviceType(),
                        d.getName(),
                        d.getStatus(),
                        d.getBrand(),
                        d.getModel()))
                .orElseGet(() -> new VerifyDeviceReferenceResponse(
                        false,
                        deviceId,
                        null,
                        null,
                        null,
                        "NOT_FOUND_OR_FORBIDDEN",
                        null,
                        null));
    }

    @Transactional(readOnly = true)
    public VerifyWarrantyResponse verifyWarrantyEligibility(UUID warrantyId, String requesterProfileId) {
        Optional<Warranty> warrantyOpt = warrantyRepository.findById(warrantyId);
        if (warrantyOpt.isEmpty() || !warrantyOpt.get().getOwnerProfileId().equals(requesterProfileId)) {
            return new VerifyWarrantyResponse(
                    false,
                    false,
                    warrantyId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "NOT_FOUND_OR_FORBIDDEN");
        }
        Warranty w = warrantyOpt.get();
        Device device = w.getDeviceId() != null ? deviceRepository.findById(w.getDeviceId()).orElse(null) : null;
        String deviceType = device != null ? device.getDeviceType() : "AC";
        if (!"ORDER_AUTO_ACTIVATED".equalsIgnoreCase(w.getSource()) || w.getOrganizationId() == null) {
            return new VerifyWarrantyResponse(
                    false,
                    false,
                    w.getId(),
                    w.getDeviceId(),
                    deviceType,
                    null,
                    w.getProviderName(),
                    w.getEndsOn(),
                    "NOT_STORE_WARRANTY");
        }
        if ("PENDING_TRIGGER".equalsIgnoreCase(w.getStatus())) {
            return new VerifyWarrantyResponse(
                    false,
                    false,
                    w.getId(),
                    w.getDeviceId(),
                    deviceType,
                    w.getOrganizationId(),
                    w.getProviderName(),
                    w.getEndsOn(),
                    "PENDING_TRIGGER");
        }
        boolean isExpired = (w.getEndsOn() == null || w.getEndsOn().isBefore(LocalDate.now()))
                || !EntityStatus.ACTIVE.name().equalsIgnoreCase(w.getStatus());
        if (isExpired) {
            return new VerifyWarrantyResponse(
                    false,
                    true,
                    w.getId(),
                    w.getDeviceId(),
                    deviceType,
                    w.getOrganizationId(),
                    w.getProviderName(),
                    w.getEndsOn(),
                    "EXPIRED");
        }
        return new VerifyWarrantyResponse(
                true,
                false,
                w.getId(),
                w.getDeviceId(),
                deviceType,
                w.getOrganizationId(),
                w.getProviderName(),
                w.getEndsOn(),
                w.getStatus());
    }

    @Transactional
    public int enqueueDueMaintenanceReminders(LocalDate referenceDate) {
        List<MaintenanceSchedule> due = scheduleRepository
                .findByStatusAndNotifiedAtIsNullAndDueOnLessThanEqual("SCHEDULED", referenceDate);
        Instant now = Instant.now();
        for (MaintenanceSchedule schedule : due) {
            schedule.setNotifiedAt(now);
            scheduleRepository.save(schedule);
            UUID eventId = UUID.nameUUIDFromBytes(
                    ("maint-due-" + schedule.getId() + "-" + schedule.getDueOn()).getBytes(StandardCharsets.UTF_8));
            if (outboxEventRepository.existsById(eventId)) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scheduleId", schedule.getId());
            data.put("deviceId", schedule.getDeviceId());
            data.put("ownerProfileId", schedule.getOwnerProfileId());
            data.put("title", schedule.getTitle());
            data.put("dueOn", schedule.getDueOn().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.MAINTENANCE_REMINDER_DUE);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "household-service");
            envelope.put("correlationId", eventId);
            envelope.put(DomainEventContracts.FIELD_DATA, data);

            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(envelope);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize household outbox payload", e);
            }

            outboxEventRepository.save(HouseholdOutboxEvent.builder()
                    .id(eventId)
                    .aggregateType("MaintenanceSchedule")
                    .aggregateId(schedule.getId())
                    .eventType(DomainEventContracts.MAINTENANCE_REMINDER_DUE)
                    .routingKey(DomainEventContracts.MAINTENANCE_REMINDER_DUE)
                    .payloadJson(payloadJson)
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        }
        return due.size();
    }

    private UUID ensureDefaultHouseholdId(String ownerProfileId) {
        return ensureDefaultHouseholdId(ownerProfileId, null);
    }

    private UUID ensureDefaultHouseholdId(String ownerProfileId, String fallbackAddress) {
        List<Household> households = householdRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId);
        if (!households.isEmpty()) {
            return households.getFirst().getId();
        }
        var activeAddresses = userAddressRepository.findActiveWithLocationByOwner(ownerProfileId);
        if (!activeAddresses.isEmpty()) {
            HouseholdResponse created = createHousehold(
                    ownerProfileId,
                    new CreateHouseholdRequest(
                            "Gia đình của tôi",
                            null,
                            "Asia/Ho_Chi_Minh",
                            activeAddresses.getFirst().getId()));
            return created.id();
        }
        String resolvedAddress = (fallbackAddress != null && !fallbackAddress.isBlank())
                ? fallbackAddress.trim()
                : "Chưa cập nhật địa chỉ";
        HouseholdResponse created = createHousehold(
                ownerProfileId,
                new CreateHouseholdRequest("Gia đình của tôi", resolvedAddress, "Asia/Ho_Chi_Minh"));
        return created.id();
    }

    private Household requireOwnedHousehold(String ownerProfileId, UUID householdId) {
        Household h = householdRepository.findById(householdId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.HOUSEHOLD_NOT_FOUND));
        if (!h.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "You do not own this household.");
        }
        return h;
    }

    private Device requireOwnedDevice(String ownerProfileId, UUID deviceId) {
        Device d = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.DEVICE_NOT_FOUND));
        if (!d.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "You do not own this device.");
        }
        return d;
    }

    private HouseholdResponse toHouseholdResponse(Household h) {
        return new HouseholdResponse(
                h.getId(),
                h.getOwnerProfileId(),
                h.getName(),
                h.getAddress(),
                h.getTimezone(),
                h.getDefaultServiceAddressId(),
                h.getCreatedAt(),
                h.getUpdatedAt());
    }

    private DeviceResponse toDeviceResponse(Device d) {
        return new DeviceResponse(
                d.getId(),
                d.getHouseholdId(),
                d.getOwnerProfileId(),
                d.getName(),
                d.getDeviceType(),
                d.getDeviceType(),
                d.getBrand(),
                d.getModel(),
                d.getSerialNumber(),
                d.getPurchaseDate(),
                d.getStatus(),
                d.getVersion(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }

    private WarrantyResponse toWarrantyResponse(Warranty w) {
        return new WarrantyResponse(
                w.getId(),
                w.getDeviceId(),
                w.getOrganizationId(),
                w.getOrderId(),
                w.getOrderLineId(),
                w.getProductId(),
                w.getSource(),
                w.getProviderName(),
                w.getOwnerName(),
                w.getStartsOn(),
                w.getStartsOn(),
                w.getEndsOn(),
                w.getEndsOn(),
                w.getNotes(),
                w.getNotes(),
                w.getDocumentUrl(),
                w.getStatus(),
                w.getManufacturerRegistrationStatus() == null ? null : w.getManufacturerRegistrationStatus().name(),
                w.getTriggerEvidenceRef(),
                w.getCreatedAt());
    }

    private MaintenanceScheduleResponse toScheduleResponse(MaintenanceSchedule s) {
        return new MaintenanceScheduleResponse(
                s.getId(),
                s.getDeviceId(),
                s.getTitle(),
                s.getCadenceMonths(),
                s.getDueOn(),
                s.getStatus(),
                s.getNotifiedAt(),
                s.getCompletedAt(),
                s.getCreatedAt());
    }

    private CareRecordResponse toCareRecordResponse(CareRecord r) {
        return new CareRecordResponse(
                r.getId(),
                r.getDeviceId(),
                r.getKind(),
                r.getKind(),
                r.getOccurredAt(),
                r.getOccurredAt(),
                r.getTitle(),
                r.getNotes(),
                r.getBookingId(),
                r.getCreatedAt());
    }
}
