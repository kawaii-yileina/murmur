package com.im.core.search.dto;

import lombok.Data;

@Data
public class ContactRow {
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String signature;
    private Integer isFriend;
}
