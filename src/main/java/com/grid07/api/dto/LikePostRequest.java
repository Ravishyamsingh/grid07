package com.grid07.api.dto;

import lombok.Data;

@Data
public class LikePostRequest {
    /** ID of the human User who is liking the post. */
    private Long userId;
}
