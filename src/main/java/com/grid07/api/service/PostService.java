package com.grid07.api.service;

import com.grid07.api.dto.CreateCommentRequest;
import com.grid07.api.dto.CreatePostRequest;
import com.grid07.api.dto.LikePostRequest;
import com.grid07.api.entity.*;
import com.grid07.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository      postRepository;
    private final CommentRepository   commentRepository;
    private final UserRepository      userRepository;
    private final BotRepository       botRepository;
    private final ViralityService     viralityService;
    private final NotificationService notificationService;

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/posts — Create a new post
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public Post createPost(CreatePostRequest req) {
        validateAuthorExists(req.getAuthorId(), req.getAuthorType());

        Post post = Post.builder()
                .authorId(req.getAuthorId())
                .authorType(req.getAuthorType())
                .content(req.getContent())
                .build();

        return postRepository.save(post);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/posts/{postId}/comments — Add a comment to a post
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public Comment addComment(Long postId, CreateCommentRequest req) {

        // 1. Verify post exists
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Post not found: " + postId));

        // 2. Verify author exists
        validateAuthorExists(req.getAuthorId(), req.getAuthorType());

        // 3. Calculate depth level
        int depthLevel = 1;
        if (req.getParentCommentId() != null) {
            Comment parent = commentRepository.findById(req.getParentCommentId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Parent comment not found: " + req.getParentCommentId()));
            depthLevel = parent.getDepthLevel() + 1;
        }

        // ── Redis guardrails (checked BEFORE any DB write) ───────────────────

        // Vertical Cap: depth must be ≤ 20
        if (!viralityService.isDepthAllowed(depthLevel)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Vertical cap exceeded: comment thread cannot go deeper than 20 levels.");
        }

        if (req.getAuthorType() == AuthorType.BOT) {
            // Horizontal Cap: ≤ 100 bot replies per post (atomic INCR)
            if (!viralityService.tryIncrementBotCount(postId)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Horizontal cap exceeded: post " + postId + " already has 100 bot replies.");
            }

            // Cooldown Cap: bot → human, once per 10 minutes
            if (req.getHumanId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "humanId is required when authorType is BOT.");
            }
            if (!viralityService.tryAcquireCooldown(req.getAuthorId(), req.getHumanId())) {
                // Roll back the bot_count increment we just did above
                viralityService.tryRollbackBotCount(postId);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Cooldown cap: this bot already interacted with this human in the last 10 minutes.");
            }
        }

        // ── All guardrails passed → commit to DB ─────────────────────────────

        Comment comment = Comment.builder()
                .postId(postId)
                .authorId(req.getAuthorId())
                .authorType(req.getAuthorType())
                .content(req.getContent())
                .depthLevel(depthLevel)
                .parentCommentId(req.getParentCommentId())
                .build();

        Comment saved = commentRepository.save(comment);

        // ── Post-save: update virality score & notifications ─────────────────

        if (req.getAuthorType() == AuthorType.BOT) {
            viralityService.addBotReplyPoints(postId);

            // Notify the human who owns the post (Phase 3)
            String botName = botRepository.findById(req.getAuthorId())
                    .map(Bot::getName)
                    .orElse("Unknown Bot");
            notificationService.handleBotInteractionNotification(
                    req.getHumanId(), botName, postId);

        } else {
            // Human comment → +50 virality
            viralityService.addHumanCommentPoints(postId);
        }

        return saved;
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/posts/{postId}/like — Like a post
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public void likePost(Long postId, LikePostRequest req) {

        // Verify post exists
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Post not found: " + postId);
        }

        // Verify user exists
        if (!userRepository.existsById(req.getUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "User not found: " + req.getUserId());
        }

        // Human Like → +20 virality (no DB column needed per assignment spec)
        viralityService.addHumanLikePoints(postId);

        log.info("User {} liked post {}. Virality updated (+20).", req.getUserId(), postId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private void validateAuthorExists(Long authorId, AuthorType authorType) {
        if (authorType == AuthorType.USER) {
            if (!userRepository.existsById(authorId)) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found: " + authorId);
            }
        } else {
            if (!botRepository.existsById(authorId)) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Bot not found: " + authorId);
            }
        }
    }
}
