package com.homecare.identity.web;

import org.springframework.stereotype.Component;

import com.homecare.common.web.CorrelationIdSupport;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;

@Component
public class CorrelationIdFilter extends ServletCorrelationIdFilter {

    public static final String HEADER_NAME = CorrelationIdSupport.HEADER_NAME;
    public static final String REQUEST_ATTRIBUTE = CorrelationIdSupport.REQUEST_ATTRIBUTE;
}
