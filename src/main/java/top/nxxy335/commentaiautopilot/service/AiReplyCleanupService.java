package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@Component
@Slf4j
public class AiReplyCleanupService implements DisposableBean {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public AiReplyCleanupService(ReactiveExtensionClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-reply-cleanup");
            t.setDaemon(true);
            return t;
        });
        // Schedule daily cleanup: initial delay 1 minute, then every 24 hours
        this.scheduler.scheduleAtFixedRate(this::dailyCleanup, 1, 24 * 60, TimeUnit.MINUTES);
    }

    public void dailyCleanup() {
        isCleanupEnabled()
            .flatMap(enabled -> {
                if (!Boolean.TRUE.equals(enabled)) {
                    log.debug("[Cleanup] Auto cleanup is disabled, skipping");
                    return Mono.empty();
                }
                return getRetentionDays()
                    .flatMap(retentionDays -> executeCleanup(retentionDays)
                        .doOnNext(deleted -> log.info("[Cleanup] Auto cleanup completed, deleted {} records older than {} days", deleted, retentionDays))
                    );
            })
            .subscribe(
                null,
                e -> log.error("[Cleanup] Error during daily cleanup: {}", e.getMessage(), e)
            );
    }

    private Mono<Boolean> isCleanupEnabled() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return false;
                String cleanupJson = data.get("cleanup");
                if (cleanupJson == null || cleanupJson.isBlank()) return true;
                try {
                    JsonNode node = objectMapper.readTree(cleanupJson);
                    return !node.has("cleanupEnabled") || node.get("cleanupEnabled").asBoolean(true);
                } catch (Exception e) {
                    log.warn("[Cleanup] Failed to parse cleanup config: {}", e.getMessage());
                    return true;
                }
            })
            .defaultIfEmpty(true);
    }

    public Mono<Long> executeCleanup(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        return client.listAll(AiCommentReply.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(r -> {
                Instant created = r.getMetadata().getCreationTimestamp();
                return created != null && created.isBefore(cutoff);
            })
            .collectList()
            .flatMap(oldRecords -> {
                if (oldRecords.isEmpty()) {
                    return Mono.just(0L);
                }
                return Flux.fromIterable(oldRecords)
                    .flatMap(record -> client.delete(record)
                        .thenReturn(1L)
                        .onErrorResume(e -> {
                            log.warn("[Cleanup] Failed to delete record {}: {}", record.getMetadata().getName(), e.getMessage());
                            return Mono.just(0L);
                        })
                    )
                    .reduce(0L, Long::sum);
            });
    }

    public Mono<Integer> getRetentionDays() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return 30;
                String cleanupJson = data.get("cleanup");
                if (cleanupJson == null || cleanupJson.isBlank()) return 30;
                try {
                    JsonNode node = objectMapper.readTree(cleanupJson);
                    return node.has("retentionDays") ? node.get("retentionDays").asInt(30) : 30;
                } catch (Exception e) {
                    return 30;
                }
            })
            .defaultIfEmpty(30)
            .onErrorResume(e -> {
                log.warn("[Cleanup] Failed to read retentionDays config: {}", e.getMessage());
                return Mono.just(30);
            });
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
