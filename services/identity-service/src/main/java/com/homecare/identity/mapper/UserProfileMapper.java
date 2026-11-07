package com.homecare.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.homecare.identity.dto.UserProfileResponse;
import com.homecare.identity.entity.UserProfile;

@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    @Mapping(target = "status", expression = "java(profile.getStatus() == null ? null : profile.getStatus().name())")
    UserProfileResponse toResponse(UserProfile profile);
}
