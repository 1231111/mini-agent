package com.miniagent.agent.memory.retriever;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryQuery;
import com.miniagent.memory.model.MemoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalHybridSearchEngineCacheTest {
    @TempDir Path temp;

    @Test void emptyOrFilteredCandidatesNeverInitializeOrCallEmbedding() {
        var model = mock(SharedEmbeddingModel.class);
        var repository = repository(List.of());
        var engine = engine(repository, model, new LocalMemoryEmbeddingCache());
        assertTrue(engine.search(query("t", "u")).isEmpty());
        when(repository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(anyString(), any(), anyString(), any()))
                .thenReturn(List.of(entity("t", "u", "content")));
        var filtered = query("t", "u");
        filtered.setMinImportance(1d);
        assertTrue(engine.search(filtered).isEmpty());
        verifyNoInteractions(model);
    }

    @Test void warmSearchComputesOnlyQueryAndReusesContentAcrossQueriesAndDuplicateEntries() {
        var model = new CountingModel(text -> vector());
        var first = entity("t", "u", "same content");
        var duplicate = entity("t", "u", "same content");
        duplicate.setId(2L);
        var engine = engine(repository(List.of(first, duplicate)), model, new LocalMemoryEmbeddingCache());
        assertEquals(2, engine.search(query("t", "u")).size());
        assertEquals(2, model.calls.get()); // query + one unique content
        var otherQuery = query("t", "u"); otherQuery.setQuery("other query");
        assertEquals(2, engine.search(otherQuery).size());
        assertEquals(3, model.calls.get());
    }

    @Test void contentUpdatesMissWithoutChangingRecallOrReusingOldVector() {
        var model = new CountingModel(text -> vector());
        var entity = entity("t", "u", "old content");
        var engine = engine(repository(List.of(entity)), model, new LocalMemoryEmbeddingCache());
        engine.search(query("t", "u"));
        entity.setContent("updated content");
        assertEquals("updated content", engine.search(query("t", "u")).getFirst().getMemory().getContent());
        assertEquals(4, model.calls.get());
        engine.search(query("t", "u"));
        assertEquals(5, model.calls.get());
    }

    @Test void tenantScopeIdAndScopeTypeHaveIndependentContentVectors() {
        var model = new CountingModel(text -> vector());
        var cache = new LocalMemoryEmbeddingCache();
        for (MemoryScope scope : List.of(MemoryScope.ofUser("t1", "u1"), MemoryScope.ofUser("t2", "u1"),
                MemoryScope.ofUser("t1", "u2"), MemoryScope.ofProject("t1", "u1"))) {
            var memory = memory(scope, "same");
            cache.contentVector(cache.modelIdentity(model), memory, 2);
            cache.contentVector(cache.modelIdentity(model), memory, 2);
        }
        assertEquals(4, model.calls.get());
    }

    @Test void modelInstanceNameEndpointRevisionAndDimensionsCannotReusePreviousVectors() {
        var environment = new MockEnvironment();
        var cache = new LocalMemoryEmbeddingCache(environment);
        var model = new CountingModel(text -> vector());
        var memory = memory(MemoryScope.ofUser("t", "u"), "same");
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        for (String property : List.of("agent.codebase.embedding-model", "agent.codebase.embedding-base-url",
                "agent.memory.vector.cache.model-revision", "agent.codebase.embedding-provider")) {
            environment.setProperty(property, "changed-" + property);
            cache.contentVector(cache.modelIdentity(model), memory, 2);
        }
        assertEquals(5, model.calls.get());
        var replacement = new CountingModel(text -> new float[]{1f, 0f, 0f});
        cache.contentVector(cache.modelIdentity(replacement), memory, 3);
        cache.contentVector(cache.modelIdentity(replacement), memory, 3);
        assertEquals(1, replacement.calls.get());
        cache.contentVector(cache.modelIdentity(replacement), memory, 2); // different dimension namespace; not cached
        cache.contentVector(cache.modelIdentity(replacement), memory, 2);
        assertEquals(3, replacement.calls.get());
    }

    @Test void localModelAndTokenizerFileVersionsInvalidateTheNamespace() throws Exception {
        Path weights = Files.writeString(temp.resolve("model.onnx"), "weights");
        Path tokenizer = Files.writeString(temp.resolve("tokenizer.json"), "tokenizer");
        var environment = new MockEnvironment().withProperty("agent.codebase.embedding-provider", "local-onnx")
                .withProperty("agent.codebase.embedding-local-model", weights.toString())
                .withProperty("agent.codebase.embedding-local-tokenizer", tokenizer.toString());
        var cache = new LocalMemoryEmbeddingCache(environment);
        var model = new CountingModel(text -> vector());
        var memory = memory(MemoryScope.ofUser("t", "u"), "content");
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        Files.writeString(weights, "changed-weights-longer");
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        Files.writeString(tokenizer, "changed-tokenizer-longer");
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        assertEquals(3, model.calls.get());
    }

    @Test void concurrentSearchesShareOneInFlightContentEmbedding() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contentCalls = new AtomicInteger();
        var model = new CountingModel(text -> {
            if (text.equals("content")) {
                contentCalls.incrementAndGet(); entered.countDown(); await(release);
            }
            return vector();
        });
        var engine = engine(repository(List.of(entity("t", "u", "content"))), model, new LocalMemoryEmbeddingCache());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = workers.submit(() -> engine.search(query("t", "u")));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() -> engine.search(query("t", "u")));
            release.countDown();
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(1, contentCalls.get());
        assertEquals(3, model.calls.get()); // two query embeddings + one shared content
    }

    @Test void emptyInvalidOrFailedContentVectorsAreRetriedAndDoNotPoisonTheCache() {
        for (float[] invalid : List.of(new float[0], new float[]{0f, 0f}, new float[]{Float.NaN, 1f},
                new float[]{Float.POSITIVE_INFINITY, 1f}, new float[]{1f})) {
            var calls = new AtomicInteger();
            var model = new CountingModel(text -> calls.incrementAndGet() == 1 ? invalid : vector());
            var cache = new LocalMemoryEmbeddingCache();
            var memory = memory(MemoryScope.ofUser("t", "u"), "content");
            cache.contentVector(cache.modelIdentity(model), memory, 2);
            cache.contentVector(cache.modelIdentity(model), memory, 2);
            cache.contentVector(cache.modelIdentity(model), memory, 2);
            assertEquals(2, model.calls.get());
        }
        var attempts = new AtomicInteger();
        var model = new CountingModel(text -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("synthetic failure");
            return vector();
        });
        var cache = new LocalMemoryEmbeddingCache();
        var memory = memory(MemoryScope.ofUser("t", "u"), "content");
        assertThrows(IllegalStateException.class, () -> cache.contentVector(cache.modelIdentity(model), memory, 2));
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        assertEquals(2, model.calls.get());
    }

    @Test void entryAndByteLimitsEvictLeastRecentlyUsedVectors() {
        for (boolean byBytes : List.of(false, true)) {
            var cache = new LocalMemoryEmbeddingCache();
            ReflectionTestUtils.setField(cache, byBytes ? "maxBytes" : "maxEntries", byBytes ? (Object) 16L : 2);
            var model = new CountingModel(text -> vector());
            var id = cache.modelIdentity(model);
            var a = memory(MemoryScope.ofUser("t", "u"), "a");
            var b = memory(MemoryScope.ofUser("t", "u"), "b");
            var c = memory(MemoryScope.ofUser("t", "u"), "c");
            cache.contentVector(id, a, 2); cache.contentVector(id, b, 2); cache.contentVector(id, a, 2);
            cache.contentVector(id, c, 2); cache.contentVector(id, a, 2); cache.contentVector(id, b, 2);
            assertEquals(4, model.calls.get());
        }
    }

    @Test void ttlExpirationAndOversizedVectorsDoNotRemainCached() {
        var now = new AtomicLong();
        var cache = new LocalMemoryEmbeddingCache(null, now::get);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 2L);
        var model = new CountingModel(text -> vector());
        var memory = memory(MemoryScope.ofUser("t", "u"), "content");
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        now.set(TimeUnit.SECONDS.toNanos(2));
        cache.contentVector(cache.modelIdentity(model), memory, 2);
        assertEquals(2, model.calls.get());
        ReflectionTestUtils.setField(cache, "maxBytes", 4L);
        var oversized = memory(MemoryScope.ofUser("t", "u"), "oversized");
        cache.contentVector(cache.modelIdentity(model), oversized, 2);
        cache.contentVector(cache.modelIdentity(model), oversized, 2);
        assertEquals(4, model.calls.get());
    }

    @Test void callersCannotMutateCachedVectors() {
        var cache = new LocalMemoryEmbeddingCache();
        var model = new CountingModel(text -> vector());
        var memory = memory(MemoryScope.ofUser("t", "u"), "content");
        float[] first = cache.contentVector(cache.modelIdentity(model), memory, 2);
        first[0] = -100;
        float[] second = cache.contentVector(cache.modelIdentity(model), memory, 2);
        assertArrayEquals(vector(), second);
        second[0] = 100;
        assertArrayEquals(vector(), cache.contentVector(cache.modelIdentity(model), memory, 2));
        assertEquals(1, model.calls.get());
    }

    @Test void boundedInFlightWaitIsInterruptibleAndDoesNotStartAnotherComputation() throws Exception {
        var cache = new LocalMemoryEmbeddingCache();
        ReflectionTestUtils.setField(cache, "maxInFlight", 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var model = new CountingModel(text -> { entered.countDown(); await(release); return vector(); });
        var id = cache.modelIdentity(model);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> owner = workers.submit(() -> cache.contentVector(id, memory(MemoryScope.ofUser("t", "u"), "a"), 2));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread waiter = Thread.ofVirtual().start(() -> {
                started.countDown();
                try {
                    assertThrows(CancellationException.class,
                            () -> cache.contentVector(id, memory(MemoryScope.ofUser("t", "u"), "b"), 2));
                } catch (Throwable error) { failure.set(error); }
                finally { finished.countDown(); }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, model.calls.get());
            release.countDown(); owner.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void missingOwnerOrDisabledCapacityDoesNotShareCacheEntries() {
        var model = new CountingModel(text -> vector());
        var cache = new LocalMemoryEmbeddingCache();
        var unowned = new MemoryEntry(); unowned.setContent("same");
        cache.contentVector(cache.modelIdentity(model), unowned, 2);
        cache.contentVector(cache.modelIdentity(model), unowned, 2);
        ReflectionTestUtils.setField(cache, "maxEntries", 0);
        var owned = memory(MemoryScope.ofUser("t", "u"), "same");
        cache.contentVector(cache.modelIdentity(model), owned, 2);
        cache.contentVector(cache.modelIdentity(model), owned, 2);
        assertEquals(4, model.calls.get());
    }

    private static LocalHybridSearchEngine engine(AgentMemoryEntryRepository repo, SharedEmbeddingModel model,
                                                   LocalMemoryEmbeddingCache cache) {
        var engine = new LocalHybridSearchEngine();
        ReflectionTestUtils.setField(engine, "entryRepository", repo);
        ReflectionTestUtils.setField(engine, "embeddingModel", model);
        ReflectionTestUtils.setField(engine, "vectorCache", cache);
        ReflectionTestUtils.setField(engine, "scoreCalculator", new HybridScoreCalculator());
        ReflectionTestUtils.setField(engine, "minScore", 0d);
        return engine;
    }

    private static AgentMemoryEntryRepository repository(List<AgentMemoryEntryEntity> entries) {
        var repository = mock(AgentMemoryEntryRepository.class);
        when(repository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(anyString(), any(), anyString(), any()))
                .thenReturn(entries);
        return repository;
    }

    private static AgentMemoryEntryEntity entity(String tenant, String owner, String content) {
        var entry = new AgentMemoryEntryEntity();
        entry.setId(1L); entry.setTenantId(tenant); entry.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        entry.setScopeId(owner); entry.setMemoryType(AgentMemoryEntryEntity.MemoryType.SEMANTIC);
        entry.setContent(content); return entry;
    }

    private static MemoryEntry memory(MemoryScope scope, String content) {
        MemoryEntry memory = MemoryEntryMapper.fromEntity(entity(scope.tenantId(), scope.scopeId(), content));
        memory.setScope(scope);
        return memory;
    }

    private static MemoryQuery query(String tenant, String owner) {
        return MemoryQuery.of("query", MemoryScope.ofUser(tenant, owner));
    }

    private static float[] vector() { return new float[]{1f, 0f}; }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static class CountingModel extends SharedEmbeddingModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final Function<String, float[]> operation;
        CountingModel(Function<String, float[]> operation) { this.operation = operation; }
        @Override public boolean isEnabled() { return true; }
        @Override public float[] embed(String text) { calls.incrementAndGet(); return operation.apply(text); }
    }
}
