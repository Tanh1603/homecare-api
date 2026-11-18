package com.homecare.commerce.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class CommerceAppProperties {

    private Vnpay vnpay = new Vnpay();
    private Commerce commerce = new Commerce();

    @Getter
    @Setter
    public static class Vnpay {
        private boolean refundEnabled = false;
    }

    @Getter
    @Setter
    public static class Commerce {
        private boolean allowExchangeReturns = false;
    }
}
