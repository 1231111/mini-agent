package com.miniagent.agent.intent;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

/**
 * 复杂任务检测：信号来自 {@link IntentProperties}，不再写死场景词。
 */
@Component
public class ComplexTaskDetector {

    @Autowired

    private IntentSignalMatcher signals;

    

    public boolean isComplex(String userMessage) {
        return signals.complex(userMessage);
    }
}
