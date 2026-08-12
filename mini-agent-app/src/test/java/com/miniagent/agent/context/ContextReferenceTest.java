package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextReferenceTest {

    @Test
    void strongReferenceLoads() {
        ContextReferenceDecision d = ContextReference.detect("刚才那份报告里的数据来源是哪一年的？");
        assertTrue(d.shouldLoadPriorHistory());
        assertFalse(d.isNegated());
    }

    @Test
    void greetingNoReference() {
        assertFalse(ContextReference.detect("你好").shouldLoadPriorHistory());
    }

    @Test
    void negationBlocksLoad() {
        ContextReferenceDecision d = ContextReference.detect("不要参考刚才那份报告，重新开始");
        assertTrue(d.hasReference());
        assertTrue(d.isNegated());
        assertFalse(d.shouldLoadPriorHistory());
    }

    @Test
    void dismissTopicBlocksLoad() {
        assertFalse(ContextReference.detect("刚才的天气不重要了，我们聊点别的").shouldLoadPriorHistory());
    }

    @Test
    void pronounReferenceLoads() {
        assertTrue(ContextReference.detect("把它改成蓝色").shouldLoadPriorHistory());
    }
}
