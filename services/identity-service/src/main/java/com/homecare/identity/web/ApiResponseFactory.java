package com.homecare.identity.web;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.homecare.common.web.servlet.ServletApiResponseFactory;

@Component
public class ApiResponseFactory extends ServletApiResponseFactory {

    public ApiResponseFactory(MessageSource messageSource) {
        super(messageSource);
    }
}
