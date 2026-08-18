package com.miniagent.agent.intent;

import org.springframework.stereotype.Component;

/** 使用温度缩放和来源偏置校准模型自报置信度。 */
@Component
public class IntentConfidenceCalibrator {

    private final IntentProperties properties;

    public IntentConfidenceCalibrator(IntentProperties properties) {
        this.properties = properties;
    }

    public double calibrate(double raw, IntentDecisionSource source) {
        double probability = clamp(raw);
        IntentProperties.Calibration calibration = properties.getCalibration();
        if (!calibration.isEnabled()) return probability;

        double bounded = Math.max(1.0e-6, Math.min(1.0 - 1.0e-6, probability));
        double logit = Math.log(bounded / (1.0 - bounded));
        double temperature = Math.max(0.05, calibration.getTemperature());
        double sourceBias = switch (source == null ? IntentDecisionSource.HEURISTIC : source) {
            case DEDICATED_MODEL -> calibration.getDedicatedModelBias();
            case FALLBACK_MODEL -> calibration.getFallbackModelBias();
            case RULE -> calibration.getRuleBias();
            case HEURISTIC -> calibration.getHeuristicBias();
            case CLARIFICATION -> 0.0;
        };
        double calibrated = 1.0 / (1.0 + Math.exp(-(logit / temperature
                + calibration.getBias() + sourceBias)));
        return clamp(calibrated);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
