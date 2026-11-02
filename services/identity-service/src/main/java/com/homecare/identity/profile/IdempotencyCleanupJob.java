package com.homecare.identity.profile;

import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IdempotencyCleanupJob {
    private final JdbcTemplate jdbc;
    public IdempotencyCleanupJob(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Scheduled(fixedDelayString = "${homecare.identity.idempotency-cleanup-interval-ms}")
    public void purgeExpired() {
        try { jdbc.update("DELETE FROM identity_idempotency WHERE expires_at <= CURRENT_TIMESTAMP"); }
        catch (org.springframework.dao.DataAccessException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("idempotency_cleanup_failed; will retry at the next scheduled interval");
        }
    }
}
