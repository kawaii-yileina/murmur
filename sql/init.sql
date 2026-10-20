CREATE DATABASE IF NOT EXISTS im_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE im_db;
SET NAMES utf8mb4;

CREATE TABLE t_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NULL COMMENT '邮箱（登录/找回密码用）',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '邮箱是否已验证 0=否 1=是',
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(255),
    signature VARCHAR(200),
    status TINYINT DEFAULT 0 COMMENT '0=offline 1=online',
    banned TINYINT NOT NULL DEFAULT 0 COMMENT '0=normal 1=banned',
    ban_reason VARCHAR(255) NULL,
    banned_at DATETIME NULL,
    banned_by BIGINT NULL,
    ban_expire_at DATETIME NULL COMMENT '封禁到期时间，NULL=永久',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否已注销 0=否 1=是',
    deleted_at DATETIME NULL COMMENT '注销时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email (email),
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_admin (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role TINYINT NOT NULL DEFAULT 1 COMMENT '0=超级管理员 1=子管理员',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=正常',
    must_change_password TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO t_admin (id, username, password, role, status, must_change_password)
VALUES (1, 'admin', '$2a$10$p6PijpRqc278rerRXrJyMuRCBxuDjFIMN.DsFQtP9iNV9gm.xcEwm', 0, 1, 1)
ON DUPLICATE KEY UPDATE username = username;

CREATE TABLE t_friend (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    friend_id BIGINT NOT NULL,
    remark VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_friend (user_id, friend_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_friend_request (
    id BIGINT PRIMARY KEY,
    from_id BIGINT NOT NULL,
    to_id BIGINT NOT NULL,
    status TINYINT DEFAULT 0 COMMENT '0=pending 1=accepted 2=rejected',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_from_to (from_id, to_id),
    INDEX idx_to_id (to_id),
    INDEX idx_from_id (from_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_group (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    avatar VARCHAR(255),
    owner_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_group_member (
    id BIGINT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role TINYINT DEFAULT 0 COMMENT '0=member 1=admin 2=owner',
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_user (group_id, user_id),
    INDEX idx_group_id (group_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_message (
    id BIGINT PRIMARY KEY,
    msg_id VARCHAR(36) NOT NULL UNIQUE,
    sender_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    chat_type TINYINT NOT NULL COMMENT '0=private 1=group',
    msg_type VARCHAR(20) NOT NULL COMMENT 'TEXT/IMAGE/VIDEO/FILE/VOICE/RECALL/CALL',
    content TEXT,
    reply_to TEXT COMMENT '引用回复信息(JSON)',
    mentions TEXT COMMENT '被@成员(JSON数组)',
    headers TEXT COMMENT '消息头信息(JSON)',
    seq BIGINT NULL COMMENT '会话内权威序号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sender_receiver (sender_id, receiver_id, created_at),
    INDEX idx_receiver (receiver_id),
    INDEX idx_receiver_seq (receiver_id, seq),
    INDEX idx_msg_id (msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_conversation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    chat_type TINYINT NOT NULL COMMENT '0=private 1=group',
    last_msg_content VARCHAR(255),
    last_msg_id VARCHAR(64),
    last_msg_time DATETIME,
    unread_count INT DEFAULT 0,
    mentioned TINYINT NOT NULL DEFAULT 0 COMMENT '是否有人@我 0=否 1=是',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_target_type (user_id, target_id, chat_type),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_ai_conversation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL COMMENT 'user/assistant/system',
    content MEDIUMTEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_session (user_id, session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_ai_session_meta (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(50) NOT NULL,
    system_prompt TEXT,
    title VARCHAR(255) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_id (session_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_user_ai_config (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    base_url VARCHAR(255) NOT NULL,
    api_key VARCHAR(255),
    model VARCHAR(100) NOT NULL,
    max_tokens INT DEFAULT 4096,
    temperature DOUBLE DEFAULT 0.7,
    enabled TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_emoji_sticker (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL COMMENT '表情图片 URL',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_url (user_id, url),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_conversation_seq (
    conversation_id VARCHAR(96) NOT NULL COMMENT '逻辑会话 id：private:min:max 或 group:{id}',
    last_seq BIGINT NOT NULL DEFAULT 0 COMMENT '该会话已分配的最大序号',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_outbox (
    id BIGINT PRIMARY KEY,
    msg_id VARCHAR(36) NOT NULL COMMENT '稳定消息身份（幂等锚点）',
    conversation_id VARCHAR(96) NOT NULL,
    seq BIGINT NOT NULL COMMENT '会话内序号',
    chat_type TINYINT NOT NULL,
    payload MEDIUMTEXT NOT NULL COMMENT '逻辑接收目标 + 原始消息 JSON',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/PROCESSING/DONE/DEAD',
    retry_count INT NOT NULL DEFAULT 0,
    lease_until DATETIME NULL COMMENT 'PROCESSING 租约到期时间',
    next_retry_at DATETIME NOT NULL COMMENT '下次可领取时间（退避）',
    last_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_claim (status, next_retry_at),
    KEY idx_conv_seq (conversation_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_call_session (
    id BIGINT PRIMARY KEY,
    call_id VARCHAR(36) NOT NULL COMMENT '通话标识',
    caller_id BIGINT NOT NULL,
    callee_id BIGINT NOT NULL,
    media_type TINYINT NOT NULL COMMENT '0=语音 1=视频',
    status VARCHAR(16) NOT NULL COMMENT 'COMPLETED/REJECTED/CANCELED/MISSED/FAILED',
    started_at DATETIME NOT NULL COMMENT '发起时间',
    answered_at DATETIME NULL COMMENT '接听时间',
    ended_at DATETIME NULL COMMENT '结束时间',
    duration_seconds INT NOT NULL DEFAULT 0 COMMENT '通话时长（秒）',
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_call_id (call_id),
    KEY idx_caller (caller_id, created_at),
    KEY idx_callee (callee_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_file_ownership (
    id BIGINT PRIMARY KEY,
    file_key VARCHAR(255) NOT NULL COMMENT '存储文件 key（如 2026/10/uuid.jpg）',
    owner_user_id BIGINT NOT NULL COMMENT '上传者（消息发送者）',
    chat_type TINYINT NOT NULL COMMENT '0=私聊 1=群聊',
    target_id BIGINT NOT NULL COMMENT '私聊为对方 userId，群聊为 groupId',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_file_key (file_key),
    KEY idx_target (chat_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
