package com.homecare.identity.profile;

/** Strict JSON object with no fields; unknown identity/role input is rejected by Jackson. */
public record EmptyRequest() {}
