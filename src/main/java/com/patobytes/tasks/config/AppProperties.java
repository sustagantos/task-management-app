package com.patobytes.tasks.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings that are not Spring's own.
 *
 * <p>{@code timezone} is deliberately a single server-side constant. "Closed
 * today" is a local-day rule, so the day boundary must never come from the
 * browser or from the JVM default - both drift, and the analytics would drift
 * with them.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(ZoneId timezone) {

    public AppProperties {
        if (timezone == null) {
            timezone = ZoneId.of("America/Sao_Paulo");
        }
    }
}
