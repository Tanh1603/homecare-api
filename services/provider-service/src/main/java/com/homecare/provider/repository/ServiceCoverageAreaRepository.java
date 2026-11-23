package com.homecare.provider.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.provider.entity.ServiceCoverageAreaEntity;

public interface ServiceCoverageAreaRepository extends JpaRepository<ServiceCoverageAreaEntity, UUID> {

    List<ServiceCoverageAreaEntity> findByServiceOfferingIdAndActiveTrueOrderByLevelAscLocationCodeAsc(UUID serviceOfferingId);

    List<ServiceCoverageAreaEntity> findByServiceOfferingIdInAndActiveTrue(Collection<UUID> serviceOfferingIds);

    void deleteByServiceOfferingId(UUID serviceOfferingId);
}
