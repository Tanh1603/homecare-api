package com.homecare.identity.profile;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Dedicated PATCH DTO preserves omitted versus explicit-null fields. */
public record ProfilePatch(JsonNode fields) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ProfilePatch {
        if (fields == null || !fields.isObject() || fields.isEmpty()
                || fields.properties().stream().anyMatch(p -> !Set.of("displayName", "phone", "address").contains(p.getKey())))
            throw new IllegalArgumentException("Invalid profile patch");
    }
}
