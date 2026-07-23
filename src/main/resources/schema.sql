-- wxbot_db 初始化脚本
-- Spring Boot 启动时自动执行（spring.sql.init.mode=always）

USE wxbot_db;

DROP TABLE IF EXISTS document_chunks;
DROP TABLE IF EXISTS user_memory;
DROP TABLE IF EXISTS weather_query;
DROP TABLE IF EXISTS file_record;
DROP TABLE IF EXISTS image_context;
DROP TABLE IF EXISTS timbre_change;
DROP TABLE IF EXISTS message;
DROP TABLE IF EXISTS conversation;

-- 会话表
CREATE TABLE conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) DEFAULT NULL,
    title VARCHAR(255) DEFAULT NULL,
    route_context VARCHAR(20) DEFAULT 'TEXT' COMMENT 'TEXT/VOICE',
    status TINYINT DEFAULT 1 COMMENT '1=进行中 0=已结束',
    message_count INT DEFAULT 0,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 消息表
CREATE TABLE message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    dialogue_id VARCHAR(64) DEFAULT NULL COMMENT '对话轮次ID',
    message_type VARCHAR(20) NOT NULL COMMENT 'USER/BOT',
    content_type VARCHAR(30) NOT NULL COMMENT 'text/image/voice/file/command',
    intent_type VARCHAR(50) DEFAULT NULL COMMENT 'ai-chat/image-gen/image-edit/tts/weather/timbre/file-recognition',
    text_content TEXT DEFAULT NULL,
    media_url VARCHAR(500) DEFAULT NULL,
    media_size BIGINT DEFAULT NULL,
    media_duration_ms INT DEFAULT NULL,
    file_name VARCHAR(255) DEFAULT NULL,
    mime_type VARCHAR(100) DEFAULT NULL,
    processing_status VARCHAR(20) DEFAULT 'SUCCESS' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED',
    error_message TEXT DEFAULT NULL,
    error_stage VARCHAR(50) DEFAULT NULL,
    elapsed_ms INT DEFAULT NULL,
    retry_count INT DEFAULT 0,
    request_id VARCHAR(128) DEFAULT NULL,
    command_prefix VARCHAR(50) DEFAULT NULL,
    voice_id VARCHAR(50) DEFAULT NULL,
    image_url_generated VARCHAR(500) DEFAULT NULL,
    session_id VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_intent_type (intent_type),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 音色切换记录表
CREATE TABLE timbre_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    old_voice_id VARCHAR(50) DEFAULT NULL,
    new_voice_id VARCHAR(50) NOT NULL,
    new_display_name VARCHAR(100) NOT NULL,
    change_source VARCHAR(30) NOT NULL COMMENT 'command/intent/keyword',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 图片上下文表
CREATE TABLE image_context (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    image_url VARCHAR(500) DEFAULT NULL,
    image_size BIGINT DEFAULT NULL,
    description TEXT DEFAULT NULL,
    edit_instruction TEXT DEFAULT NULL,
    is_ref_image TINYINT DEFAULT 0,
    expired_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id_created (user_id, created_at),
    INDEX idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 文件识别记录表
CREATE TABLE file_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_type_label VARCHAR(50) NOT NULL,
    md5_hash VARCHAR(32) DEFAULT NULL,
    extracted_text TEXT DEFAULT NULL,
    text_length INT DEFAULT NULL,
    ai_analysis TEXT DEFAULT NULL,
    analysis_elapsed_ms INT DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message TEXT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id_created (user_id, created_at),
    INDEX idx_message_id (message_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 天气查询记录表
CREATE TABLE weather_query (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    query_text VARCHAR(500) NOT NULL,
    city VARCHAR(100) NOT NULL,
    query_type VARCHAR(20) NOT NULL COMMENT 'now/forecast/multi-day',
    api_response TEXT DEFAULT NULL,
    report_text TEXT DEFAULT NULL,
    api_elapsed_ms INT DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id_created (user_id, created_at),
    INDEX idx_city (city),
    INDEX idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- RAG: 文档切片表（文件上传后经文本提取、智能切片、向量化后存入）
CREATE TABLE document_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_record_id BIGINT DEFAULT NULL,
    user_id VARCHAR(128) NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    chunk_size INT DEFAULT 0,
    embedding JSON DEFAULT NULL COMMENT 'float[] vector as JSON array',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_dc_file_record (file_record_id),
    INDEX idx_dc_user_id (user_id),
    FOREIGN KEY (file_record_id) REFERENCES file_record(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- RAG: 用户历史记忆表（对话摘要、偏好、行为等长期记忆向量化存储）
CREATE TABLE user_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    memory_type VARCHAR(50) NOT NULL COMMENT 'chat/preference/behavior',
    content TEXT NOT NULL,
    embedding JSON DEFAULT NULL COMMENT 'float[] vector as JSON array',
    importance FLOAT DEFAULT 0.5,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_um_user_type (user_id, memory_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
