package com.miniagent.memory.service.evolution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

@ConfigurationProperties(prefix = "memory.evolution")
@Profile("memory-service")
public record EvolutionProperties(boolean enabled, int minimumSamples, double maximumBaselineFailureRate,
                                  double minimumImprovement, int canaryPercent, int minimumCanarySamples,
                                  double rollbackFailureRate) {
    public EvolutionProperties {
        if (minimumSamples < 1 || minimumCanarySamples < 1)
            throw new IllegalArgumentException("evolution sample thresholds must be positive");
        if (!ratio(maximumBaselineFailureRate) || !ratio(minimumImprovement)
                || !ratio(rollbackFailureRate) || canaryPercent < 1 || canaryPercent > 100)
            throw new IllegalArgumentException("invalid evolution ratios");
    }

    private static boolean ratio(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
}
