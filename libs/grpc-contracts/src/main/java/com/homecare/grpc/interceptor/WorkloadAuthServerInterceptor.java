package com.homecare.grpc.interceptor;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public class WorkloadAuthServerInterceptor implements ServerInterceptor {

    private final String expectedWorkloadToken;

    public WorkloadAuthServerInterceptor(String expectedWorkloadToken) {
        this.expectedWorkloadToken = expectedWorkloadToken;
    }

    public void verifyMetadata(Metadata headers) {
        if (expectedWorkloadToken != null && !expectedWorkloadToken.isBlank()) {
            String provided = headers == null ? null : headers.get(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY);
            if (provided == null || !expectedWorkloadToken.equals(provided)) {
                throw new StatusRuntimeException(
                        Status.UNAUTHENTICATED.withDescription("Missing or invalid X-Internal-Workload-Token"));
            }
        }
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        try {
            verifyMetadata(headers);
            return next.startCall(call, headers);
        } catch (StatusRuntimeException ex) {
            call.close(ex.getStatus(), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
    }
}
