package com.miniagent.agent.memory.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentWorkingMemoryEntity;
import com.miniagent.agent.memory.repository.AgentWorkingMemoryRepository;
import com.miniagent.memory.model.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkingMemoryManagerMergeTest {

    @Mock
    private AgentWorkingMemoryRepository repository;

    private WorkingMemoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkingMemoryManager();
        ReflectionTestUtils.setField(manager, "repository", repository);
        ReflectionTestUtils.setField(manager, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(manager, "redisTemplate", null);
    }

    @Test
    void update_合并变量与已完成任务_不覆盖未提及字段() {
        AgentWorkingMemoryEntity existing = new AgentWorkingMemoryEntity();
        existing.setSessionId("s1");
        existing.setGoal("旧目标");
        existing.setVariablesJson("{\"a\":1}");
        existing.setCompletedTasksJson("[\"t1\"]");

        when(repository.findById("s1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkingMemory patch = new WorkingMemory();
        patch.setGoal("新目标");
        patch.setCompletedTasks(List.of("t2"));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("b", 2);
        patch.setVariables(vars);

        manager.update("s1", patch);

        ArgumentCaptor<AgentWorkingMemoryEntity> cap =
                ArgumentCaptor.forClass(AgentWorkingMemoryEntity.class);
        verify(repository).save(cap.capture());
        AgentWorkingMemoryEntity saved = cap.getValue();

        assertEquals("新目标", saved.getGoal());
        assertTrue(saved.getCompletedTasksJson().contains("t1"));
        assertTrue(saved.getCompletedTasksJson().contains("t2"));
        assertTrue(saved.getVariablesJson().contains("\"a\":1"));
        assertTrue(saved.getVariablesJson().contains("\"b\":2"));
    }
}
