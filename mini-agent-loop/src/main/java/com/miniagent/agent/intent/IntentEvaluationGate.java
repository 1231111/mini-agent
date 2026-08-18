package com.miniagent.agent.intent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 意图模型发布前的离线/影子评测门禁。它不依赖线上模型，便于 CI 直接调用。
 */
@Component
public class IntentEvaluationGate {

    public record EvaluationCase(String input, IntentType expected) {
        public EvaluationCase {
            input = Objects.requireNonNullElse(input, "");
            expected = expected == null ? IntentType.UNKNOWN : expected;
        }
    }

    public record Report(int total, int correct, int disagreements,
                         double precision, double regressionRate,
                         boolean promotable, String reason) {}

    private final IntentProperties properties;

    public IntentEvaluationGate(IntentProperties properties) {
        this.properties = properties;
    }

    public Report evaluate(List<EvaluationCase> cases,
                           Function<String, IntentType> predictor) {
        List<EvaluationCase> data = cases == null ? List.of() : cases;
        if (data.isEmpty() || predictor == null)
            return new Report(data.size(), 0, 0, 0.0, 1.0, false,
                    "评测集或预测器为空");
        int correct = 0;
        int disagreements = 0;
        for (EvaluationCase item : data) {
            IntentType actual;
            try { actual = predictor.apply(item.input()); }
            catch (Exception e) { actual = IntentType.UNKNOWN; }
            if (actual == item.expected()) correct++;
            else disagreements++;
        }
        double precision = (double) correct / data.size();
        double regressionRate = (double) disagreements / data.size();
        IntentProperties.Rollout rollout = currentProperties();
        boolean pass = data.size() >= Math.max(1, rollout.getShadowMinObservations())
                && precision >= rollout.getMinPrecision()
                && regressionRate <= rollout.getMaxRegressionRate()
                && ((double) disagreements / data.size()) <= rollout.getMaxDisagreementRate();
        String reason = pass ? "通过发布门禁"
                : "precision/regression/disagreement 未达到配置阈值";
        return new Report(data.size(), correct, disagreements, precision,
                regressionRate, pass, reason);
    }

    private IntentProperties.Rollout currentProperties() {
        return properties == null ? new IntentProperties().getRollout() : properties.getRollout();
    }
}
