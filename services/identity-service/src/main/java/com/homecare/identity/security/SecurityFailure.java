package com.homecare.identity.security;

public class SecurityFailure extends RuntimeException {
    public final int status;
    public final String code;
    public SecurityFailure(int status, String code) { super(code); this.status = status; this.code = code; }
}
