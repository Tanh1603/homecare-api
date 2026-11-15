package com.homecare.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ComposeConfigValidationTest {
    @Test
    void infraComposeAndInitScriptDefineAllSixServiceDatabasesAndRabbitMq() throws Exception {
        String infra = Files.readString(Path.of("../../compose.infra.yml"));
        String script = Files.readString(Path.of("../../docker/postgres/init-multiple-dbs.sh"));
        String app = Files.readString(Path.of("../../compose.app.yml"));
        assertThat(infra).contains("rabbitmq:").contains("init-multiple-dbs.sh");
        assertThat(script).contains("household_db", "provider_db",
                "booking_db", "commerce_db", "review_db", "notification_db");
        assertThat(app).doesNotContain("9083:9083").contains("- \"9083\""); // internal gRPC port only
        String commerce = app.substring(app.indexOf("  commerce-service:"), app.indexOf("  review-service:"));
        assertThat(commerce).contains("PROVIDER_GRPC_ADDRESS: \"static://provider-service:9083\"");
    }
}
