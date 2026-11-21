package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordMedia;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class MediaDownload {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    static byte[] fetch(String url) throws Exception {
        URI uri = URI.create(DiscordMedia.downloadUrl(url));
        var body = new LimitedBody(MediaImage.MAX_BYTES);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "image/png,image/gif,image/jpeg,image/*;q=0.8").GET().build();
        var response = HTTP.sendAsync(request, info -> {
            if (info.statusCode() != 200 || info.headers().firstValueAsLong("content-length").orElse(0) > MediaImage.MAX_BYTES)
                body.reject();
            return body;
        });
        try { return response.get(20, TimeUnit.SECONDS).body(); }
        finally { body.cancel(); if (!response.isDone()) response.cancel(true); }
    }
    /** Does not buffer an unbounded HTTP body before checking its size. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; if (result.isDone()) s.cancel(); else s.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) { cancel(); return; }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { reject(); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        void reject() { result.completeExceptionally(new IOException("Media response rejected")); cancel(); }
        void cancel() { Flow.Subscription s = subscription; if (s != null) s.cancel(); }
    }
}
