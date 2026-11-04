package com.homecare.identity.web;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.homecare.identity.dto.ApiMessageResponse;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApiMessageFactory {

    private final MessageSource messageSource;

    public ApiMessageResponse create(String messageKey) {
        return new ApiMessageResponse(messageSource.getMessage(messageKey, null, Locale.ENGLISH));
    }
}
