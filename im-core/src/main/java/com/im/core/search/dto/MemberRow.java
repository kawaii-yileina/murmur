package com.im.core.search.dto;

import lombok.Data;

@Data
public class MemberRow {
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private Long groupId;
    private String groupName;
    private Integer role;
}
