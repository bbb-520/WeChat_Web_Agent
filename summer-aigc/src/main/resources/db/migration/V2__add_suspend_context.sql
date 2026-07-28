-- V2__add_suspend_context.sql
-- Add suspend support to conversation table for Agent loop pause/resume

ALTER TABLE conversation
    ADD COLUMN suspend_context JSON DEFAULT NULL
        COMMENT 'Serialized suspend state: tool name, pending data, message history snapshot',
    ADD COLUMN suspend_reason VARCHAR(255) DEFAULT NULL
        COMMENT 'Human-readable reason for suspension, shown to user on resume';

-- Update existing status comment to reflect new state
ALTER TABLE conversation
    MODIFY COLUMN status TINYINT DEFAULT 1
        COMMENT '0=已结束 1=进行中 2=挂起等待用户输入';
