package com.homecare.provider.repository;

import com.homecare.provider.entity.CategoryQualificationEntity;
import com.homecare.provider.entity.ServiceCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryQualificationRepository extends JpaRepository<CategoryQualificationEntity, UUID> {

    Optional<CategoryQualificationEntity> findByProviderIdAndCategory(UUID providerId, ServiceCategory category);

    List<CategoryQualificationEntity> findByProviderId(UUID providerId);
}
