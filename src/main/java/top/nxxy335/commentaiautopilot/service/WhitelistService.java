package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AI 审核白名单服务。
 *
 * <p>白名单内的评论者跳过前置过滤与拦截逻辑，确保管理员与可信用户的评论不被误伤。
 * 判定优先级：
 * <ol>
 *   <li>whitelistEnabled=false → 直接返回 false（白名单未启用）</li>
 *   <li>评论者 owner.kind == "User"（已登录 Halo 用户）且为管理员（super-role 或 role-admin）→ 始终白名单 true</li>
 *   <li>其他已登录用户 → displayName 或 name 命中 whitelistedCommenters 配置列表 → 返回 true</li>
 *   <li>邮箱/匿名评论者 → displayName 命中 whitelistedCommenters 配置列表 → 返回 true</li>
 *   <li>其余情况返回 false</li>
 * </ol>
 */
@Component
@Slf4j
public class WhitelistService {

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";
    /** Halo 已登录用户作为 Comment.owner 时的 kind 值。 */
    private static final String KIND_USER = User.KIND;
    /** Halo 超级管理员角色名（拥有全部权限）。 */
    private static final String SUPER_ROLE = "super-role";

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    public WhitelistService(ReactiveExtensionClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * 检查指定评论的评论者是否在白名单中。
     *
     * @param commentName Comment metadata.name
     * @return true 表示在白名单内（应跳过拦截），false 表示不在白名单
     */
    public Mono<Boolean> isWhitelisted(String commentName) {
        if (commentName == null || commentName.isBlank()) {
            return Mono.just(false);
        }
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(cm -> {
                WhitelistConfig config = parseConfig(cm);
                if (!config.enabled) {
                    // 白名单未启用，所有评论都不算白名单
                    return Mono.just(false);
                }
                return client.fetch(Comment.class, commentName)
                    .flatMap(comment -> evaluateWhitelist(comment, config))
                    .defaultIfEmpty(false);
            })
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                log.warn("[Whitelist] 检查白名单失败: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    /**
     * 读取白名单启用状态与名单列表（用于端点展示）。
     */
    public Mono<WhitelistConfig> getConfig() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .map(this::parseConfig)
            .defaultIfEmpty(new WhitelistConfig(true, ""));
    }

    /**
     * 写入白名单评论者列表到 ConfigMap（端点调用）。
     */
    public Mono<Void> updateWhitelistedCommenters(List<String> commenters) {
        String joined = commenters == null ? "" : String.join("\n", commenters);
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(cm -> {
                var data = cm.getData();
                if (data == null) {
                    data = new java.util.HashMap<>();
                    cm.setData(data);
                }
                String basicJson = data.getOrDefault("basic", "{}");
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    ObjectNode objectNode = node.isObject()
                        ? (ObjectNode) node.deepCopy()
                        : objectMapper.createObjectNode();
                    objectNode.put("whitelistedCommenters", joined);
                    data.put("basic", objectMapper.writeValueAsString(objectNode));
                } catch (Exception e) {
                    log.warn("[Whitelist] 序列化白名单失败，使用裸 JSON 写入: {}", e.getMessage());
                    // 退化方案：直接拼接 JSON
                    data.put("basic", "{\"whitelistedCommenters\":" + objectMapper.valueToTree(joined) + "}");
                }
                return client.update(cm);
            })
            .then();
    }

    /**
     * 清空白名单评论者列表（端点调用）。
     */
    public Mono<Void> clearWhitelistedCommenters() {
        return updateWhitelistedCommenters(Collections.emptyList());
    }

    // ──────────────────────────────────────────────────────────────
    // 私有方法
    // ──────────────────────────────────────────────────────────────

    private Mono<Boolean> evaluateWhitelist(Comment comment, WhitelistConfig config) {
        var owner = comment.getSpec() != null ? comment.getSpec().getOwner() : null;
        if (owner == null) {
            return Mono.just(false);
        }
        if (KIND_USER.equals(owner.getKind()) && owner.getName() != null && !owner.getName().isBlank()) {
            return isAdminUser(owner.getName())
                .map(isAdmin -> isAdmin || isInWhitelist(owner.getDisplayName(), config.list())
                    || isInWhitelist(owner.getName(), config.list()));
        }
        return Mono.just(matchByDisplayName(owner, config.list()));
    }

    private Mono<Boolean> isAdminUser(String username) {
        return client.fetch(User.class, username)
            .map(user -> {
                var annotations = user.getMetadata().getAnnotations();
                if (annotations == null) return false;
                String roleNames = annotations.get(User.ROLE_NAMES_ANNO);
                if (roleNames == null || roleNames.isBlank()) return false;
                return Arrays.stream(roleNames.split(","))
                    .map(String::trim)
                    .anyMatch(r -> SUPER_ROLE.equals(r) || "role-admin".equals(r));
            })
            .defaultIfEmpty(false)
            .onErrorResume(e -> Mono.just(false));
    }

    private boolean matchByDisplayName(Comment.CommentOwner owner, List<String> whitelist) {
        String displayName = owner.getDisplayName();
        return isInWhitelist(displayName, whitelist);
    }

    private boolean isInWhitelist(String value, List<String> whitelist) {
        if (value == null || value.isBlank() || whitelist.isEmpty()) return false;
        return whitelist.stream().anyMatch(item -> item != null && !item.isBlank()
            && item.trim().equalsIgnoreCase(value.trim()));
    }

    private WhitelistConfig parseConfig(ConfigMap cm) {
        if (cm == null || cm.getData() == null) {
            return new WhitelistConfig(true, "");
        }
        String basicJson = cm.getData().get("basic");
        if (basicJson == null || basicJson.isBlank()) {
            return new WhitelistConfig(true, "");
        }
        try {
            JsonNode node = objectMapper.readTree(basicJson);
            boolean enabled = !node.has("whitelistEnabled")
                || node.get("whitelistEnabled").asBoolean(true);
            String commentersStr = node.has("whitelistedCommenters")
                ? node.get("whitelistedCommenters").asText("") : "";
            return new WhitelistConfig(enabled, commentersStr);
        } catch (Exception e) {
            log.warn("[Whitelist] 解析配置失败: {}", e.getMessage());
            return new WhitelistConfig(true, "");
        }
    }

    /**
     * 白名单配置记录。
     *
     * @param enabled whitelistEnabled 开关
     * @param rawList whitelistedCommenters 原始字符串（每行一个 name 或逗号分隔）
     */
    public record WhitelistConfig(boolean enabled, String rawList) {
        /**
         * 将原始字符串解析为白名单列表。支持换行或逗号分隔。
         */
        public List<String> list() {
            if (rawList == null || rawList.isBlank()) return Collections.emptyList();
            return Arrays.stream(rawList.split("[\n,]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }
    }

    public Mono<AdminListResult> getAdminList() {
        return client.list(User.class, null, null)
            .collectList()
            .map(users -> {
                List<AdminRef> superAdmins = new java.util.ArrayList<>();
                List<AdminRef> admins = new java.util.ArrayList<>();
                for (User user : users) {
                    var annotations = user.getMetadata().getAnnotations();
                    String roleNames = annotations != null ? annotations.get(User.ROLE_NAMES_ANNO) : null;
                    boolean isSuper = false;
                    boolean isAdmin = false;
                    if (roleNames != null && !roleNames.isBlank()) {
                        List<String> roles = Arrays.stream(roleNames.split(","))
                            .map(String::trim)
                            .toList();
                        isSuper = roles.contains(SUPER_ROLE);
                        isAdmin = roles.contains("role-admin");
                    }
                    String displayName = user.getSpec() != null && user.getSpec().getDisplayName() != null
                        ? user.getSpec().getDisplayName() : user.getMetadata().getName();
                    String email = user.getSpec() != null && user.getSpec().getEmail() != null
                        ? user.getSpec().getEmail() : "";
                    AdminRef ref = new AdminRef(displayName, email, user.getMetadata().getName());
                    if (isSuper) {
                        superAdmins.add(ref);
                    } else if (isAdmin) {
                        admins.add(ref);
                    }
                }
                return new AdminListResult(superAdmins, admins);
            })
            .onErrorResume(e -> {
                log.warn("[Whitelist] 获取管理员列表失败: {}", e.getMessage());
                return Mono.just(new AdminListResult(java.util.Collections.emptyList(), java.util.Collections.emptyList()));
            });
    }

    public record AdminRef(String displayName, String email, String username) {}
    public record AdminListResult(List<AdminRef> superAdmins, List<AdminRef> admins) {}
}
