package com.grid07.api.scheduler;

import com.grid07.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;



/**
 * Phase 3 — CRON Sweeper.
 *
 * Runs every 5 minutes (simulating the 15-minute production sweep as per spec).
 * For each user with pending notifications in Redis:
 *  1. Pops all queued messages.
 *  2. Counts them.
 *  3. Logs a single summarised notification.
 *  4. Clears the Redis list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSweeper {

    private final NotificationService notificationService;

    /**
     * Cron: every 5 minutes.
     * fixedRate = 5 * 60 * 1000 ms
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void sweep() {
        log.info("[NotificationSweeper] Running sweep...");

        Set<String> pendingKeys = notificationService.getAllPendingNotifKeys();

        if (pendingKeys == null || pendingKeys.isEmpty()) {
            log.info("[NotificationSweeper] No pending notifications found.");
            return;
        }

        for (String key : pendingKeys) {
            // key format: user:{id}:pending_notifs
            Long userId = extractUserId(key);
            if (userId == null) continue;

            List<String> messages = notificationService.popAllPendingNotifications(userId);
            if (messages.isEmpty()) continue;

            int count = messages.size();

            // Parse the first message to extract the bot name
            // Message format: "Bot <name> replied to your post (postId: X)"
            String firstBotName = extractBotName(messages.get(0));

            if (count == 1) {
                log.info("Summarized Push Notification to User {}: {} interacted with your posts.",
                        userId, firstBotName);
            } else {
                log.info("Summarized Push Notification to User {}: {} and [{}] others interacted with your posts.",
                        userId, firstBotName, count - 1);
            }
        }

        log.info("[NotificationSweeper] Sweep complete. Processed {} user(s).",
                pendingKeys.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long extractUserId(String key) {
        try {
            // "user:{id}:pending_notifs"
            String[] parts = key.split(":");
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractBotName(String message) {
        try {
            // "Bot <name> replied to your post (postId: X)"
            String afterBot = message.substring("Bot ".length());
            return afterBot.substring(0, afterBot.indexOf(" replied"));
        } catch (Exception e) {
            return "Unknown Bot";
        }
    }
}
