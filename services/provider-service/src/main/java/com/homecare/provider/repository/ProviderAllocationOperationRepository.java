package com.homecare.provider.repository;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import com.homecare.provider.entity.ProviderAllocationOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProviderAllocationOperationRepository extends JpaRepository<ProviderAllocationOperation, UUID> {

    @Lock(PESSIMISTIC_WRITE)
    @Query("select op from ProviderAllocationOperation op where op.operationId = :operationId")
    Optional<ProviderAllocationOperation> lockByOperationId(UUID operationId);
}
