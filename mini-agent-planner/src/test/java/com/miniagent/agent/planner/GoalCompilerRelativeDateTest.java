package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.web.SearchDateContext;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalCompilerRelativeDateTest {

    @Test
    void compilationAndCorrectionBothReceiveCurrentDateInConfiguredTimezone() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        GoalCompiler compiler = new GoalCompiler(properties);
        ReflectionTestUtils.setField(compiler, "configuredTimeZone", "Pacific/Honolulu");
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("test response"));
        String request = "帮我搜索今日 AI 技术最新动态";
        ZoneId zone = ZoneId.of("Pacific/Honolulu");
        LocalDate before = LocalDate.now(zone);

        compiler.compile(chat, request, TaskPlan.of(request));
        compiler.compileWithCorrection(chat, request, TaskPlan.of(request), "保留用户日期范围");

        LocalDate after = LocalDate.now(zone);
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat, times(2)).chat(requests.capture());
        for (ChatRequest sent : requests.getAllValues()) {
            String system = ((SystemMessage) sent.messages().getFirst()).text();
            assertTrue(system.contains("时区是 Pacific/Honolulu"), system);
            assertTrue(List.of(before, after).stream().anyMatch(day ->
                    system.contains("dateFrom=" + day) && system.contains("dateTo=" + day)), system);
            assertTrue(system.contains("禁止擅自扩大为最近24/48小时"), system);
            assertTrue(system.contains("constraints") && system.contains("acceptanceCriteria"), system);
        }
    }

    @Test
    void invalidConfiguredTimezoneUsesSameShanghaiFallbackAsSearch() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        GoalCompiler compiler = new GoalCompiler(properties);
        ReflectionTestUtils.setField(compiler, "configuredTimeZone", "invalid-zone");
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("test response"));

        compiler.compile(chat, "搜索今日新闻", TaskPlan.of("搜索今日新闻"));

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).chat(request.capture());
        String system = ((SystemMessage) request.getValue().messages().getFirst()).text();
        assertTrue(system.contains("时区是 Asia/Shanghai"), system);
    }

    @Test
    void compilationAndCorrectionPreserveTheOriginalRequestDayAndAsOfTime() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        GoalCompiler compiler = new GoalCompiler(properties);
        ReflectionTestUtils.setField(compiler, "configuredTimeZone", "Pacific/Honolulu");
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("test response"));
        SearchDateContext.Window window = new SearchDateContext.Window(
                LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-12"),
                ZoneId.of("Asia/Shanghai"), Instant.parse("2026-11-12T15:59:59Z"));
        String request = "搜索今日新闻";

        try (SearchDateContext.Scope ignored = SearchDateContext.bind(window)) {
            compiler.compile(chat, request, TaskPlan.of(request));
            compiler.compileWithCorrection(chat, request, TaskPlan.of(request), "重试规划");
        }

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat, times(2)).chat(requests.capture());
        for (ChatRequest sent : requests.getAllValues()) {
            String system = ((SystemMessage) sent.messages().getFirst()).text();
            assertTrue(system.contains("时区是 Asia/Shanghai"), system);
            assertTrue(system.contains("dateFrom=2026-11-12"), system);
            assertTrue(system.contains("dateTo=2026-11-12"), system);
            assertTrue(system.contains("2026-11-12T23:59:59+08:00"), system);
        }
    }
}
