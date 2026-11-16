package com.miniagent.agent.memory.retriever;

import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded, process-local content vectors. Query text and memory content are never retained as cache keys. */
@Component
public class LocalMemoryEmbeddingCache {
    private static final String PREFIX = "agent.codebase.";
    private final Environment environment;
    private final LongSupplier clock;
    private final Object mutex = new Object();
    private final LinkedHashMap<Key, Cached> values = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, CompletableFuture<float[]>> inFlight = new LinkedHashMap<>();
    private long bytes;
    private long lastSweepNanos;
    private boolean swept;

    @Value("${agent.memory.vector.cache.max-entries:2048}")
    private int maxEntries = 2048;
    @Value("${agent.memory.vector.cache.max-bytes:33554432}")
    private long maxBytes = 33_554_432L;
    @Value("${agent.memory.vector.cache.ttl-seconds:1800}")
    private long ttlSeconds = 1800L;
    @Value("${agent.memory.vector.cache.max-in-flight:32}")
    private int maxInFlight = 32;

    @Autowired
    public LocalMemoryEmbeddingCache(Environment environment) {
        this(environment, System::nanoTime);
    }

    LocalMemoryEmbeddingCache() {
        this(null, System::nanoTime);
    }

    LocalMemoryEmbeddingCache(Environment environment, LongSupplier clock) {
        this.environment = environment;
        this.clock = clock;
    }

    /** Capture once per search, so a query and all its candidate vectors use one namespace. */
    ModelIdentity modelIdentity(SharedEmbeddingModel model) {
        String provider = property(PREFIX + "embedding-provider", "remote");
        String descriptor = provider + "\n" + property(PREFIX + "embedding-base-url", "https://api.siliconflow.cn/v1")
                + "\n" + property(PREFIX + "embedding-model", "BAAI/bge-m3")
                + "\n" + property("agent.memory.vector.cache.model-revision", "1");
        if ("local-onnx".equalsIgnoreCase(provider.trim())) {
            String modelPath = property(PREFIX + "embedding-local-model", "");
            descriptor += "\n" + fileStamp(modelPath) + "\n" + fileStamp(modelPath + ".data")
                    + "\n" + fileStamp(property(PREFIX + "embedding-local-tokenizer", ""));
        }
        return new ModelIdentity(model, digest(descriptor));
    }

    float[] contentVector(ModelIdentity model, MemoryEntry memory, int dimensions) {
        if (memory.getContent() == null || memory.getContent().isBlank()) return new float[0];
        MemoryScope scope = memory.getScope();
        // Missing owner metadata must never share cached vectors with another owner.
        if (scope == null || scope.tenantId() == null || scope.scopeType() == null || scope.scopeId() == null)
            return model.instance.embed(memory.getContent());
        Key key = new Key(model, scope.tenantId(), scope.scopeType().name(), scope.scopeId(),
                digest(memory.getContent()), dimensions);
        return load(key, () -> model.instance.embed(memory.getContent()));
    }

    private float[] load(Key key, Supplier<float[]> compute) {
        if (maxEntries <= 0 || maxBytes <= 0 || ttlSeconds <= 0) return compute.get();
        CompletableFuture<float[]> pending;
        boolean owner;
        synchronized (mutex) {
            while (true) {
                long now = clock.getAsLong();
                prune(now);
                Cached cached = values.get(key);
                if (cached != null) {
                    if (now - cached.createdNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(ttlSeconds))
                        return cached.vector.clone();
                    values.remove(key);
                    bytes -= cached.vector.length * (long) Float.BYTES;
                }
                pending = inFlight.get(key);
                if (pending != null) { owner = false; break; }
                if (inFlight.size() < Math.max(1, maxInFlight)) {
                    pending = new CompletableFuture<>();
                    inFlight.put(key, pending);
                    owner = true;
                    break;
                }
                try { mutex.wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Memory vector cache wait interrupted");
                }
            }
        }
        if (!owner) return await(pending);
        try {
            float[] vector = compute.get();
            float[] retained = vector == null ? new float[0] : vector.clone();
            synchronized (mutex) {
                if (cacheable(retained, key.dimensions) && retained.length * (long) Float.BYTES <= maxBytes) {
                    values.put(key, new Cached(retained, clock.getAsLong()));
                    bytes += retained.length * (long) Float.BYTES;
                    prune(clock.getAsLong());
                }
            }
            pending.complete(retained);
            return retained.clone();
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            throw propagate(failure);
        } finally {
            synchronized (mutex) {
                inFlight.remove(key, pending);
                mutex.notifyAll();
            }
        }
    }

    private static float[] await(CompletableFuture<float[]> pending) {
        try { return pending.get().clone(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Memory vector cache wait interrupted");
        } catch (ExecutionException failed) { throw propagate(failed.getCause()); }
    }

    private void prune(long now) {
        long ttlNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(ttlSeconds);
        // Do not turn an N-candidate search into N full cache sweeps. Requested keys
        // check their TTL above; idle expired values are swept at most once a second.
        if (!swept || now - lastSweepNanos >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) {
            lastSweepNanos = now;
            swept = true;
            Iterator<Cached> expired = values.values().iterator();
            while (expired.hasNext()) {
                Cached entry = expired.next();
                if (now - entry.createdNanos >= ttlNanos) {
                    bytes -= entry.vector.length * (long) Float.BYTES;
                    expired.remove();
                }
            }
        }
        Iterator<Cached> oldest = values.values().iterator();
        while ((values.size() > maxEntries || bytes > maxBytes) && oldest.hasNext()) {
            bytes -= oldest.next().vector.length * (long) Float.BYTES;
            oldest.remove();
        }
    }

    private static boolean cacheable(float[] vector, int dimensions) {
        if (dimensions <= 0 || vector.length != dimensions) return false;
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) return false;
            nonzero |= value != 0;
        }
        return nonzero;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Memory vector computation failed", failure);
    }

    private String property(String key, String fallback) {
        return environment == null ? fallback : environment.getProperty(key, fallback);
    }

    private static String fileStamp(String configuredPath) {
        if (configuredPath.isBlank()) return "missing";
        try {
            Path path = Path.of(configuredPath).toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return path + "|" + attributes.size() + "|" + attributes.lastModifiedTime();
        } catch (IOException | RuntimeException missing) { return configuredPath + "|unavailable"; }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static final class ModelIdentity {
        private final SharedEmbeddingModel instance;
        private final String configuration;
        private ModelIdentity(SharedEmbeddingModel instance, String configuration) {
            this.instance = instance;
            this.configuration = configuration;
        }
        @Override public boolean equals(Object other) {
            return other instanceof ModelIdentity that && instance == that.instance
                    && configuration.equals(that.configuration);
        }
        @Override public int hashCode() { return Objects.hash(System.identityHashCode(instance), configuration); }
    }

    private record Key(ModelIdentity model, String tenant, String scopeType, String scopeId,
                       String contentSha256, int dimensions) {}
    private record Cached(float[] vector, long createdNanos) {}
}
