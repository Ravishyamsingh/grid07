package com.grid07.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Phase 3 — Notification Engine (Smart Batching).
 *
 * When a bot interacts with a user's post:
 *  - If the user has NOT been notified in the last 15 minutes →
 *      log "Push Notification Sent to User" and set a 15-minute cooldown.
 *  - If the user HAS been notified recently →
 *      push the notification string into a Redis List for later batching.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String NOTIF_COOLDOWN_KEY  = "notif:cooldown:user_%d";
    private static final String PENDING_NOTIFS_KEY  = "user:%d:pending_notifs";
    private static final Duration NOTIF_COOLDOWN_TTL = Duration.ofMinutes(15);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Called after a bot successfully comments on a user's post.
     *
     * @param userId  ID of the human who owns the post
     * @param botName Name of the bot that interacted
     * @param postId  ID of the post (for the notification message)
     */
    public void handleBotInteractionNotification(Long userId, String botName, Long postId) {
        String cooldownKey = String.format(NOTIF_COOLDOWN_KEY, userId);
        String pendingKey  = String.format(PENDING_NOTIFS_KEY, userId);
        String message     = String.format("Bot %s replied to your post (postId: %d)", botName, postId);

        // SET NX EX — atomic; succeeds only if the key does not exist
        Boolean isFirst = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", NOTIF_COOLDOWN_TTL);

        if (Boolean.TRUE.equals(isFirst)) {
            // No recent notification — send immediately
            log.info("Push Notification Sent to User {}: {}", userId, message);
        } else {
            // Already notified recently — queue for batch delivery
            redisTemplate.opsForList().rightPush(pendingKey, message);
            log.debug("Queued pending notification for user {}: {}", userId, message);
        }
    }

    /**
     * Called by the CRON sweeper to fetch and clear all pending notifications
     * for a given user.
     *
     * @return list of pending notification strings (may be empty)
     */
    public java.util.List<String> popAllPendingNotifications(Long userId) {
        String key = String.format(PENDING_NOTIFS_KEY, userId);
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return java.util.Collections.emptyList();
        }
        // LRANGE 0 -1 then DEL
        java.util.List<String> messages =
                redisTemplate.opsForList().range(key, 0, -1);
        redisTemplate.delete(key);
        return messages == null ? java.util.Collections.emptyList() : messages;
    }

    /**
     * Returns all Redis keys that match the pending-notifications pattern.
     * Used by the sweeper to discover which users have pending items.
     */
    public java.util.Set<String> getAllPendingNotifKeys() {
        return redisTemplate.keys("user:*:pending_notifs");
    }
}
