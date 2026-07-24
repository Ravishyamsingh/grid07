package com.grid07.api.dto;

import com.grid07.api.entity.AuthorType;
import lombok.Data;

@Data
public class CreatePostRequest {
    private Long authorId;
    private AuthorType authorType;
    private String content;
}


