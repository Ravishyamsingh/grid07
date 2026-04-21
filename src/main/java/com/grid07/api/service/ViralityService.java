package com.grid07.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Handles all Redis-based guardrails and virality scoring.
 *
 * Thread-safety strategy
 * ──────────────────────
 * Redis INCR is an atomic single-command operation; no two threads can
 * interleave reads and writes around it.  We exploit this for the Horizontal
 * Cap:
 *
 *   long count = INCR post:{id}:bot_count
 *   if (count > 100) → DECR and reject
 *
 * Because the increment and the subsequent check are done *after* the atomic
 * INCR, the first 100 requests that reach count ≤ 100 are all allowed; the
 * 101st (and every concurrent one that already hit 101+) is rejected and
 * decremented.  This guarantees the database never sees more than 100 bot
 * comments per post even under a 200-concurrent-thread spam test.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViralityService {

    // ── Redis key templates ──────────────────────────────────────────────────
    private static final String VIRALITY_KEY    = "post:%d:virality_score";
    private static final String BOT_COUNT_KEY   = "post:%d:bot_count";
    private static final String COOLDOWN_KEY    = "cooldown:bot_%d:human_%d";

    // ── Limits ───────────────────────────────────────────────────────────────
    private static final long     HORIZONTAL_CAP      = 100L;
    private static final int      VERTICAL_CAP        = 20;
    private static final Duration COOLDOWN_TTL        = Duration.ofMinutes(10);

    // ── Virality points ──────────────────────────────────────────────────────
    private static final long BOT_REPLY_POINTS    = 1L;
    private static final long HUMAN_LIKE_POINTS   = 20L;
    private static final long HUMAN_COMMENT_POINTS = 50L;

    private final RedisTemplate<String, String> redisTemplate;

    // ════════════════════════════════════════════════════════════════════════
    // Phase 2-A: Virality Score
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Rolls back a bot_count increment if a subsequent guardrail (e.g. cooldown)
     * rejects the request after the horizontal cap was already incremented.
     */
    public void tryRollbackBotCount(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        redisTemplate.opsForValue().decrement(key);
    }

    /** Called when a bot replies to a post/comment. */
    public void addBotReplyPoints(Long postId) {
        redisTemplate.opsForValue().increment(
                String.format(VIRALITY_KEY, postId), BOT_REPLY_POINTS);
    }

    /** Called when a human likes a post. */
    public void addHumanLikePoints(Long postId) {
        redisTemplate.opsForValue().increment(
                String.format(VIRALITY_KEY, postId), HUMAN_LIKE_POINTS);
    }

    /** Called when a human comments on a post. */
    public void addHumanCommentPoints(Long postId) {
        redisTemplate.opsForValue().increment(
                String.format(VIRALITY_KEY, postId), HUMAN_COMMENT_POINTS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Phase 2-B: Atomic Guardrails
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Horizontal Cap — enforces ≤ 100 bot replies per post.
     *
     * Atomically increments the counter.  If the result exceeds the cap we
     * immediately decrement it back so the true count stays accurate.
     *
     * @return true  → allowed (counter was ≤ 100 after increment)
     *         false → rejected (counter was > 100, already rolled back)
     */
    public boolean tryIncrementBotCount(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null || count > HORIZONTAL_CAP) {
            // Roll back so the persisted count remains accurate
            redisTemplate.opsForValue().decrement(key);
            return false;
        }
        return true;
    }

    /**
     * Vertical Cap — rejects comment threads deeper than 20 levels.
     *
     * @return true  → allowed
     *         false → rejected
     */
    public boolean isDepthAllowed(int depthLevel) {
        return depthLevel <= VERTICAL_CAP;
    }

    /**
     * Cooldown Cap — a bot may interact with a specific human at most once
     * per 10 minutes.
     *
     * Uses Redis SET NX EX (set-if-not-exists with TTL), which is atomic.
     *
     * @return true  → no active cooldown; key was set (interaction allowed)
     *         false → cooldown is active (interaction blocked)
     */
    public boolean tryAcquireCooldown(Long botId, Long humanId) {
        String key = String.format(COOLDOWN_KEY, botId, humanId);
        // setIfAbsent is the Spring abstraction for SET key value NX PX millis
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", COOLDOWN_TTL);
        return Boolean.TRUE.equals(set);
    }
}
