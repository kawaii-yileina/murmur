package com.im.core.search.dto;

import lombok.Data;

@Data
public class GroupRow {
    private Long id;
    private String name;
    private String avatar;
    private Long ownerId;
}
