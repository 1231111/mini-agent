package com.miniagent.application;

import com.miniagent.config.entity.ChatMessage;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.ChatMessageRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.service.AgentToolHistoryCodec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTaskContinuationServiceTest {
    private AnnotationConfigApplicationContext context;
    private ChatTaskRepository tasks;
    private ChatMessageRepository messages;
    private ConversationTaskContinuationService service;

    @BeforeEach void setUp() {
        context = new AnnotationConfigApplicationContext(Configuration.class);
        tasks = context.getBean(ChatTaskRepository.class);
        messages = context.getBean(ChatMessageRepository.class);
        service = context.getBean(ConversationTaskContinuationService.class);
    }
    @AfterEach void close() { context.close(); }

    @Test void continuationUpdatesTheOriginalCardAndAnswerWithoutAddingAUserTurn() {
        ChatTask original = task(7L, "session", "exec-original", "Build a multiplayer UI", "waiting", 5);
        ChatMessage user = message("session", "user", original.getQuestion(), 10, null);
        ChatMessage assistant = message("session", "assistant", "waiting", 11, "{\"oldTools\":true}");
        message("other-session", "assistant", "waiting", 12, "other");
        task(7L, "session", "exec-other", "Other task", "other answer", 8);

        var binding = service.prepare(7L, "session", "exec-original");
        assertThat(binding.chatTaskId()).isEqualTo(original.getId());
        assertThat(binding.question()).isEqualTo("Build a multiplayer UI");
        assertThat(binding.taskId()).isEqualTo(41);
        service.complete(binding, "all files verified", progress("exec-original", 41, 9));

        assertThat(tasks.count()).isEqualTo(2);
        ChatTask updated = tasks.findById(original.getId()).orElseThrow();
        assertThat(updated.getQuestion()).isEqualTo(original.getQuestion());
        assertThat(updated.getAnswer()).isEqualTo("all files verified");
        assertThat(TaskProgressSnapshot.fromJson(updated.getTaskProgressJson()).version()).isEqualTo(9);
        assertThat(messages.count()).isEqualTo(3);
        assertThat(messages.findById(user.getId()).orElseThrow().getContent()).isEqualTo(original.getQuestion());
        ChatMessage refreshed = messages.findById(assistant.getId()).orElseThrow();
        assertThat(refreshed.getContent()).isEqualTo("all files verified");
        assertThat(refreshed.getToolHistoryJson()).isEqualTo("{\"oldTools\":true}");
    }

    @Test void staleBindingsAndOtherExecutionsCannotOverwriteTheOriginalAnswer() {
        ChatTask task = task(7L, "session", "exec-one", "Build UI", "waiting", 5);
        ChatMessage assistant = message("session", "assistant", "waiting", 10, "tools");
        var binding = service.prepare(7L, "session", "exec-one");

        assertThat(service.prepare(8L, "session", "exec-one")).isNull();
        assertThat(service.prepare(7L, "session", "exec-two")).isNull();
        assertThatThrownBy(() -> service.complete(8L, "session", binding, "evil", progress("exec-one", 41, 6)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.complete(binding, "bad execution", progress("exec-two", 41, 6)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.complete(binding, "bad task", progress("exec-one", 42, 6)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.complete(binding, "old version", progress("exec-one", 41, 4)))
                .isInstanceOf(IllegalStateException.class);

        service.complete(binding, "new result", progress("exec-one", 41, 6));
        assertThatThrownBy(() -> service.complete(binding, "stale result", progress("exec-one", 41, 7)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findById(task.getId()).orElseThrow().getAnswer()).isEqualTo("new result");
        assertThat(messages.findById(assistant.getId()).orElseThrow().getContent()).isEqualTo("new result");
    }

    @Test void assistantConflictRollsBackTheTaskCardUpdate() {
        ChatTask original = task(7L, "session", "exec-one", "Build UI", "waiting", 5);
        ChatMessage assistant = message("session", "assistant", "waiting", 10, "tools");
        var binding = service.prepare(7L, "session", "exec-one");
        messages.compareAndSetContent(assistant.getId(), "session", "assistant", "waiting", "newer answer");

        assertThatThrownBy(() -> service.complete(binding, "stale answer", progress("exec-one", 41, 6)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("助手消息");

        ChatTask after = tasks.findById(original.getId()).orElseThrow();
        assertThat(after.getAnswer()).isEqualTo("waiting");
        assertThat(TaskProgressSnapshot.fromJson(after.getTaskProgressJson()).version()).isEqualTo(5);
        assertThat(messages.findById(assistant.getId()).orElseThrow().getContent()).isEqualTo("newer answer");
    }

    @Test void mergesCompletedContinuationToolExchangesAndPreservesExactReasoning() {
        task(7L, "session", "exec-one", "Build UI", "waiting", 5);
        var previousMessages = new java.util.ArrayList<>(exchanges("old-call", "read_file", "old result", "old reasoning"));
        previousMessages.add(AiMessage.builder().text("waiting").thinking("old answer reasoning").build());
        String previous = AgentToolHistoryCodec.encodeExchanges(previousMessages, true);
        ChatMessage assistant = message("session", "assistant", "waiting", 10, previous);
        var binding = service.prepare(7L, "session", "exec-one");
        var resumed = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        resumed.add(UserMessage.from("internal continuation"));
        resumed.addAll(exchanges("new-call", "search_code", "new result", "new reasoning"));

        try (var model = com.miniagent.common.model.EffectiveModelContext.bind(null, null, 0, true)) {
            service.complete(binding, "verified", progress("exec-one", 41, 6), resumed);
        }

        String history = messages.findById(assistant.getId()).orElseThrow().getToolHistoryJson();
        var decoded = AgentToolHistoryCodec.decode(history);
        assertThat(decoded).hasSize(4);
        assertThat(((AiMessage) decoded.get(0)).toolExecutionRequests().getFirst().id()).isEqualTo("old-call");
        assertThat(((AiMessage) decoded.get(0)).thinking()).isEqualTo("old reasoning");
        assertThat(((AiMessage) decoded.get(2)).toolExecutionRequests().getFirst().id()).isEqualTo("new-call");
        assertThat(((AiMessage) decoded.get(2)).thinking()).isEqualTo("new reasoning");
        assertThat(((ToolExecutionResultMessage) decoded.get(3)).text()).isEqualTo("new result");
        assertThat(tasks.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test void firstTaskScopeZeroIsPreservedAndAnAbsentAssistantCannotBePartiallyUpdated() {
        ChatTask original = task(7L, "session", "exec-first", "Build UI", "waiting", 5);
        original.setTaskProgressJson(progress("exec-first", 0, 5).toJson());
        tasks.saveAndFlush(original);
        assertThat(service.prepare(7L, "session", "exec-first")).isNull();
        message("session", "assistant", "waiting", 10, null);
        var binding = service.prepare(7L, "session", "exec-first");
        assertThat(binding.taskId()).isZero();
        service.complete(binding, "verified", progress("exec-first", 0, 6));
        assertThat(TaskProgressSnapshot.fromJson(tasks.findById(original.getId()).orElseThrow()
                .getTaskProgressJson()).taskId()).isZero();
    }

    private List<dev.langchain4j.data.message.ChatMessage> exchanges(String id, String name, String result, String thinking) {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
        return List.of(AiMessage.builder().text("").thinking(thinking).toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from(id, name, result));
    }

    private ChatTask task(Long owner, String session, String execution, String question, String answer, long version) {
        ChatTask task = new ChatTask(); task.setUserId(owner); task.setSessionId(session);
        task.setQuestion(question); task.setAnswer(answer);
        task.setTaskProgressJson(progress(execution, 41, version).toJson());
        return tasks.saveAndFlush(task);
    }
    private ChatMessage message(String session, String role, String text, long at, String tools) {
        ChatMessage message = new ChatMessage(); message.setConversationId(session); message.setRole(role);
        message.setContent(text); message.setTimestamp(at); message.setToolHistoryJson(tools);
        return messages.saveAndFlush(message);
    }
    private TaskProgressSnapshot progress(String execution, long taskId, long version) {
        return new TaskProgressSnapshot(execution, taskId, "WAITING", "AWAITING_CONFIRM", List.of(),
                List.of(), "Build a multiplayer UI", version, "", Map.of());
    }

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ChatTaskRepository.class)
    static class Configuration {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:continuation-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.miniagent.config.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
        @Bean ConversationTaskContinuationService service(ChatTaskRepository tasks, ChatMessageRepository messages) {
            return new ConversationTaskContinuationService(tasks, messages);
        }
    }
}
