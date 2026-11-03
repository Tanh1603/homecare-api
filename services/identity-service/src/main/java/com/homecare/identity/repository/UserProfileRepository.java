package com.homecare.identity.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.identity.domain.UserProfile;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
}
