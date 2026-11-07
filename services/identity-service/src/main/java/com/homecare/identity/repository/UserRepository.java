package com.homecare.identity.repository;

import com.homecare.identity.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserProfile, UUID> {
    Optional<UserProfile> findByKeycloakUserId(String keycloakUserId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}