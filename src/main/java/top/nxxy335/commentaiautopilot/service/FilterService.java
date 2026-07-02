package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FilterService {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";
    private static final String ANNOTATION_KEY = "comment-ai-autopilot.nxxy335.top/ai-reply-enabled";
    private static final String GROUP_CONTENT = "content.halo.run";

    public FilterService(ReactiveExtensionClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public Mono<Boolean> shouldProcess(Comment comment) {
        return checkBlockedCommenters(comment)
            .flatMap(blocked -> {
                if (blocked) {
                    return Mono.just(false);
                }
                return checkAnnotationEnabled(comment);
            })
            .defaultIfEmpty(true)
            .onErrorResume(e -> {
                log.warn("[Filter] Error checking filter rules: {}", e.getMessage());
                return Mono.just(true);
            });
    }

    public Mono<Boolean> shouldProcess(String commentName) {
        return client.fetch(Comment.class, commentName)
            .flatMap(this::shouldProcess)
            .defaultIfEmpty(true)
            .onErrorResume(e -> {
                log.warn("[Filter] Error fetching comment for filter check: {}", e.getMessage());
                return Mono.just(true);
            });
    }

    /**
     * 检查评论者是否在黑名单中（按 commentName 查询）。
     */
    public Mono<Boolean> isCommenterBlocked(String commentName) {
        return client.fetch(Comment.class, commentName)
            .flatMap(this::checkBlockedCommenters)
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                log.warn("[Filter] Error checking blocked commenter: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<Boolean> checkBlockedCommenters(Comment comment) {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return false;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return false;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    String blockedCommentersStr = node.has("blockedCommenters")
                        ? node.get("blockedCommenters").asText("") : "";
                    List<String> blockedCommenters = parseList(blockedCommentersStr);
                    String commenterName = getCommenterDisplayName(comment);
                    String commenterEmail = getCommenterEmail(comment);
                    if (isInList(commenterName, blockedCommenters) || isInList(commenterEmail, blockedCommenters)) {
                        log.info("[Filter] Commenter '{}' (email: '{}') is in blocked list, skipping", commenterName, commenterEmail);
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    log.warn("[Filter] Failed to parse basic config: {}", e.getMessage());
                    return false;
                }
            })
            .defaultIfEmpty(false);
    }

    private Mono<Boolean> checkAnnotationEnabled(Comment comment) {
        if (comment.getSpec() == null || comment.getSpec().getSubjectRef() == null) {
            return Mono.just(true);
        }
        var subjectRef = comment.getSpec().getSubjectRef();
        String group = subjectRef.getGroup();
        String kind = subjectRef.getKind();
        String name = subjectRef.getName();

        if (GROUP_CONTENT.equals(group) && "Post".equals(kind)) {
            return client.fetch(Post.class, name)
                .map(post -> resolveAnnotation(post.getMetadata().getAnnotations(), true))
                .defaultIfEmpty(true);
        }

        if (GROUP_CONTENT.equals(group) && "SinglePage".equals(kind)) {
            return client.fetch(SinglePage.class, name)
                .map(page -> resolveAnnotation(page.getMetadata().getAnnotations(), false))
                .defaultIfEmpty(false);
        }

        // 瞬间插件评论：读取 momentsEnabled 配置（默认开启）
        if ("Moment".equals(kind)) {
            return getMomentsEnabled();
        }

        // Unknown subjectRef type, default to allowing
        return Mono.just(true);
    }

    /**
     * 读取瞬间评论区适配开关配置。
     */
    private Mono<Boolean> getMomentsEnabled() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return true;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return true;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (!node.has("momentsEnabled")) {
                        return true;
                    }
                    return node.get("momentsEnabled").asBoolean(true);
                } catch (Exception e) {
                    log.warn("[Filter] Failed to parse momentsEnabled: {}", e.getMessage());
                    return true;
                }
            })
            .onErrorResume(e -> {
                log.debug("[Filter] Failed to fetch momentsEnabled: {}", e.getMessage());
                return Mono.just(true);
            })
            .defaultIfEmpty(true);
    }

    private boolean resolveAnnotation(java.util.Map<String, String> annotations, boolean defaultEnabled) {
        if (annotations == null || !annotations.containsKey(ANNOTATION_KEY)) {
            return defaultEnabled;
        }
        String value = annotations.get(ANNOTATION_KEY);
        if ("false".equalsIgnoreCase(value)) {
            log.info("[Filter] Annotation {} is set to false, skipping", ANNOTATION_KEY);
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        // Unrecognized value, fall back to default
        return defaultEnabled;
    }

    private String getCommenterDisplayName(Comment comment) {
        if (comment.getSpec() == null || comment.getSpec().getOwner() == null) return "";
        var displayName = comment.getSpec().getOwner().getDisplayName();
        return displayName != null ? displayName : "";
    }

    private String getCommenterEmail(Comment comment) {
        if (comment.getSpec() == null || comment.getSpec().getOwner() == null) return "";
        var owner = comment.getSpec().getOwner();
        if (Comment.CommentOwner.KIND_EMAIL.equals(owner.getKind())) {
            var name = owner.getName();
            return name != null ? name : "";
        }
        return "";
    }

    private List<String> parseList(String str) {
        if (str == null || str.isBlank()) return Collections.emptyList();
        return Arrays.stream(str.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    private boolean isInList(String value, List<String> list) {
        if (value == null || value.isEmpty() || list.isEmpty()) return false;
        return list.stream().anyMatch(item -> {
            if (item.startsWith("regex:")) {
                try {
                    String regex = item.substring(6);
                    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                    return pattern.matcher(value).matches();
                } catch (Exception e) {
                    log.warn("[Filter] Invalid regex pattern '{}': {}", item, e.getMessage());
                    return false;
                }
            }
            return item.equalsIgnoreCase(value);
        });
    }
}
