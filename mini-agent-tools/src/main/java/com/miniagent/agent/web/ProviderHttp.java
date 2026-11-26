package com.miniagent.agent.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded provider responses. The deadline includes headers and the entire body. */
final class ProviderHttp {
    private ProviderHttp() {}

    static HttpResponse.BodyHandler<byte[]> bytes(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        return info -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> result = new CompletableFuture<>();
            private final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            private Flow.Subscription subscription;
            @Override public CompletionStage<byte[]> getBody() { return result; }
            @Override public void onSubscribe(Flow.Subscription incoming) {
                subscription = incoming;
                if (info.headers().firstValueAsLong("Content-Length").orElse(-1) > maximum) {
                    incoming.cancel();
                    result.completeExceptionally(new IOException("Provider response exceeds " + maximum + " bytes"));
                } else incoming.request(1);
            }
            @Override public void onNext(List<ByteBuffer> buffers) {
                if (result.isDone()) return;
                for (ByteBuffer buffer : buffers) {
                    if ((long) output.size() + buffer.remaining() > maximum) {
                        subscription.cancel();
                        result.completeExceptionally(new IOException("Provider response exceeds " + maximum + " bytes"));
                        return;
                    }
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                }
                subscription.request(1);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(output.toByteArray()); }
        };
    }

    static HttpResponse.BodyHandler<String> utf8(int maximum) {
        return info -> HttpResponse.BodySubscribers.mapping(bytes(maximum).apply(info),
                body -> new String(body, StandardCharsets.UTF_8));
    }

    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                  HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> pending = client.sendAsync(request, handler);
        try {
            return pending.get(request.timeout().orElse(Duration.ofSeconds(30)).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new java.net.http.HttpTimeoutException("Provider response body deadline exceeded");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Provider transport failed", cause);
        }
    }
}
