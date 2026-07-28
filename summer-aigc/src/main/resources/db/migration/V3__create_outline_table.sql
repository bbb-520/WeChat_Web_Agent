-- V3__create_outline_table.sql
-- Structured document outline storage for createOutline -> confirm -> generateDocument flow

CREATE TABLE IF NOT EXISTS document_outline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    outline_type VARCHAR(20) NOT NULL COMMENT 'WORD/PPT/EXCEL',
    title VARCHAR(500) DEFAULT NULL COMMENT '文档标题',
    outline_data JSON NOT NULL COMMENT '结构化大纲数据',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/CONFIRMED/MODIFIED/GENERATING/DONE',
    version INT DEFAULT 1 COMMENT '修改版本号，每次用户确认+1',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_outline_type (outline_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
