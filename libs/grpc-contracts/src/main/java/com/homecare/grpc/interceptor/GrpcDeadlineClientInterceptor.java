package com.homecare.grpc.interceptor;

import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;

public class GrpcDeadlineClientInterceptor implements ClientInterceptor {

    private final long defaultTimeoutMillis;

    public GrpcDeadlineClientInterceptor(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis > 0 ? defaultTimeoutMillis : 3000L;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        CallOptions effectiveOptions = callOptions.getDeadline() != null
                ? callOptions
                : callOptions.withDeadlineAfter(defaultTimeoutMillis, TimeUnit.MILLISECONDS);
        return next.newCall(method, effectiveOptions);
    }
}
