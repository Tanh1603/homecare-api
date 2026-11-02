package com.homecare.identity.profile;

public class ProfileFailure extends RuntimeException {
    public final int status;
    public final String code;
    public ProfileFailure(int status, String code) { super(code); this.status = status; this.code = code; }
}
