package com.homecare.identity.security;

/** Only constructed from the verified Firebase token; never from caller headers. */
public record VerifiedSubject(String project, String uid) {}
