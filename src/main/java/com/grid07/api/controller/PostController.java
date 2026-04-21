package com.grid07.api.controller;

import com.grid07.api.dto.CreateCommentRequest;
import com.grid07.api.dto.CreatePostRequest;
import com.grid07.api.dto.LikePostRequest;
import com.grid07.api.entity.Comment;
import com.grid07.api.entity.Post;
import com.grid07.api.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    // ── POST /api/posts ──────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Post> createPost(@RequestBody CreatePostRequest req) {
        Post created = postService.createPost(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── POST /api/posts/{postId}/comments ────────────────────────────────────
    @PostMapping("/{postId}/comments")
    public ResponseEntity<Comment> addComment(
            @PathVariable Long postId,
            @RequestBody CreateCommentRequest req) {
        Comment created = postService.addComment(postId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── POST /api/posts/{postId}/like ────────────────────────────────────────
    @PostMapping("/{postId}/like")
    public ResponseEntity<Void> likePost(
            @PathVariable Long postId,
            @RequestBody LikePostRequest req) {
        postService.likePost(postId, req);
        return ResponseEntity.ok().build();
    }
}
