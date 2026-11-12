package com.homecare.grpc.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

public class WorkloadAuthClientInterceptor implements ClientInterceptor {

    public static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            Metadata.Key.of("x-internal-workload-token", Metadata.ASCII_STRING_MARSHALLER);

    private final String workloadToken;

    public WorkloadAuthClientInterceptor(String workloadToken) {
        this.workloadToken = workloadToken;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (workloadToken != null && !workloadToken.isBlank()) {
                    headers.put(WORKLOAD_TOKEN_KEY, workloadToken);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
