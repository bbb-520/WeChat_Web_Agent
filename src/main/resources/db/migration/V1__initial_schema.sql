USE wxbot_db;

-- ============================================================
-- 修正已有 conversation 表的字段类型（确保 suspend_reason 为 TEXT）
-- ============================================================
ALTER TABLE conversation
    MODIFY COLUMN suspend_reason TEXT DEFAULT NULL
        COMMENT '挂起原因（展示给用户）';

-- 同时修正 suspend_context 为 JSON（如果之前不是 JSON，此语句可安全执行）
ALTER TABLE conversation
    MODIFY COLUMN suspend_context JSON DEFAULT NULL
        COMMENT '序列化的挂起状态: tool name, pending data, message history snapshot';

-- ============================================================
-- 会话表 (含 V2 suspend 字段 + V3 字段类型修正)
-- ============================================================
CREATE TABLE IF NOT EXISTS conversation (
                                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            user_id VARCHAR(128) NOT NULL,
                                            session_id VARCHAR(64) DEFAULT NULL,
                                            title VARCHAR(255) DEFAULT NULL,
                                            route_context VARCHAR(20) DEFAULT 'TEXT'        COMMENT 'TEXT/VOICE',
                                            status TINYINT DEFAULT 1                         COMMENT '0=已结束 1=进行中 2=挂起等待用户输入',
                                            message_count INT DEFAULT 0,
                                            start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                                            end_time DATETIME DEFAULT NULL,
                                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- V2: 挂起支持
                                            suspend_context JSON DEFAULT NULL                COMMENT '序列化的挂起状态: tool name, pending data, message history snapshot',
                                            suspend_reason  TEXT DEFAULT NULL                COMMENT '挂起原因（展示给用户）',

                                            INDEX idx_user_id (user_id),
                                            INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 消息表
-- ============================================================
CREATE TABLE IF NOT EXISTS message (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       user_id VARCHAR(128) NOT NULL,
                                       conversation_id BIGINT DEFAULT NULL,
                                       dialogue_id VARCHAR(64) DEFAULT NULL            COMMENT '对话轮次ID',
                                       message_type VARCHAR(20) NOT NULL               COMMENT 'USER/BOT',
                                       content_type VARCHAR(30) NOT NULL               COMMENT 'text/image/voice/file/command',
                                       intent_type VARCHAR(50) DEFAULT NULL            COMMENT 'ai-chat/image-gen/image-edit/tts/weather/timbre/file-recognition',
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

-- ============================================================
-- 音色切换记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS timbre_change (
                                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             user_id VARCHAR(128) NOT NULL,
                                             conversation_id BIGINT DEFAULT NULL,
                                             old_voice_id VARCHAR(50) DEFAULT NULL,
                                             new_voice_id VARCHAR(50) NOT NULL,
                                             new_display_name VARCHAR(100) NOT NULL,
                                             change_source VARCHAR(30) NOT NULL            COMMENT 'command/intent/keyword',
                                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             INDEX idx_user_id (user_id),
                                             INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 图片上下文表
-- ============================================================
CREATE TABLE IF NOT EXISTS image_context (
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

-- ============================================================
-- 文件识别记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS file_record (
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

-- ============================================================
-- 天气查询记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS weather_query (
                                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             user_id VARCHAR(128) NOT NULL,
                                             message_id VARCHAR(64) NOT NULL DEFAULT '',
                                             query_text VARCHAR(500) NOT NULL,
                                             city VARCHAR(100) NOT NULL,
                                             query_type VARCHAR(20) NOT NULL               COMMENT 'now/forecast/multi-day',
                                             api_response TEXT DEFAULT NULL,
                                             report_text TEXT DEFAULT NULL,
                                             api_elapsed_ms INT DEFAULT NULL,
                                             status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
                                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             INDEX idx_user_id_created (user_id, created_at),
                                             INDEX idx_city (city),
                                             INDEX idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 成语接龙游戏记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS idiom_game_record (
                                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                 user_id VARCHAR(128) NOT NULL,
                                                 score INT NOT NULL DEFAULT 0,
                                                 rounds INT NOT NULL DEFAULT 0,
                                                 end_reason VARCHAR(20) NOT NULL DEFAULT 'USER_STOP' COMMENT 'USER_WIN/USER_STOP/TIMEOUT',
                                                 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                 INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- RAG: 文档切片表
-- ============================================================
CREATE TABLE IF NOT EXISTS document_chunks (
                                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                               file_record_id BIGINT DEFAULT NULL,
                                               user_id VARCHAR(128) NOT NULL,
                                               chunk_index INT NOT NULL,
                                               chunk_text TEXT NOT NULL,
                                               chunk_size INT DEFAULT 0,
                                               embedding JSON DEFAULT NULL                 COMMENT 'float[] vector as JSON array',
                                               created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               INDEX idx_dc_file_record (file_record_id),
                                               INDEX idx_dc_user_id (user_id),
                                               FOREIGN KEY (file_record_id) REFERENCES file_record(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- RAG: 用户历史记忆表
-- ============================================================
CREATE TABLE IF NOT EXISTS user_memory (
                                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           user_id VARCHAR(128) NOT NULL,
                                           memory_type VARCHAR(50) NOT NULL            COMMENT 'chat/preference/behavior',
                                           content TEXT NOT NULL,
                                           embedding JSON DEFAULT NULL                 COMMENT 'float[] vector as JSON array',
                                           importance FLOAT DEFAULT 0.5,
                                           created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                           INDEX idx_um_user_type (user_id, memory_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 结构化文档大纲表 (V3)
-- ============================================================
CREATE TABLE IF NOT EXISTS document_outline (
                                                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                user_id VARCHAR(128) NOT NULL,
                                                conversation_id BIGINT DEFAULT NULL,
                                                outline_type VARCHAR(20) NOT NULL            COMMENT 'WORD/PPT/EXCEL',
                                                title VARCHAR(500) DEFAULT NULL              COMMENT '文档标题',
                                                outline_data JSON NOT NULL                    COMMENT '结构化大纲数据',
                                                status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'   COMMENT 'DRAFT/CONFIRMED/MODIFIED/GENERATING/DONE',
                                                version INT DEFAULT 1                         COMMENT '修改版本号，每次用户确认+1',
                                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                                INDEX idx_user_id (user_id),
                                                INDEX idx_conversation_id (conversation_id),
                                                INDEX idx_outline_type (outline_type),
                                                INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 生成文档记录表 (V4)
-- ============================================================
CREATE TABLE IF NOT EXISTS document_record (
                                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                               user_id VARCHAR(128) NOT NULL,
                                               conversation_id BIGINT DEFAULT NULL,
                                               outline_id BIGINT DEFAULT NULL,
                                               document_type VARCHAR(20) NOT NULL           COMMENT 'WORD/PPT/EXCEL',
                                               file_name VARCHAR(500) NOT NULL              COMMENT '生成的文件名',
                                               file_path VARCHAR(1000) DEFAULT NULL         COMMENT '文件存储路径',
                                               file_size BIGINT DEFAULT NULL                COMMENT '文件大小（字节）',
                                               status VARCHAR(20) NOT NULL DEFAULT 'GENERATED' COMMENT 'GENERATING/GENERATED/FAILED',
                                               idempotency_key VARCHAR(128) NOT NULL        COMMENT '幂等键：outline_id + version + type',
                                               created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                               UNIQUE KEY uk_idempotency_key (idempotency_key),
                                               INDEX idx_user_id (user_id),
                                               INDEX idx_conversation_id (conversation_id),
                                               INDEX idx_outline_id (outline_id),
                                               INDEX idx_document_type (document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;