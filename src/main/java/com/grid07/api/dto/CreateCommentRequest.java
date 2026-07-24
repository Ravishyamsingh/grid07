
package com.grid07.api.dto;

import com.grid07.api.entity.AuthorType;
import lombok.Data;

@Data
public class CreateCommentRequest {

    private Long authorId;
    private AuthorType authorType;
    private String content;

    /**
     * Optional. If provided, this is a reply to an existing comment.
     * depth_level will be parentComment.depthLevel + 1.
     * If null, this is a top-level comment (depth_level = 1).
     */
    private Long parentCommentId;

    /**
     * Required when authorType == BOT.
     * The ID of the human (User) who owns the post or parent comment.
     * Used to enforce the cooldown cap: one bot → one human per 10 minutes.
     */
    private Long humanId;
}
