-- V4__create_document_record.sql
-- Generated document tracking with idempotency support

CREATE TABLE IF NOT EXISTS document_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    outline_id BIGINT DEFAULT NULL,
    document_type VARCHAR(20) NOT NULL COMMENT 'WORD/PPT/EXCEL',
    file_name VARCHAR(500) NOT NULL COMMENT '生成的文件名',
    file_path VARCHAR(1000) DEFAULT NULL COMMENT '文件存储路径',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小（字节）',
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATED'
        COMMENT 'GENERATING/GENERATED/FAILED',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键：outline_id + version + type',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_outline_id (outline_id),
    INDEX idx_document_type (document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
