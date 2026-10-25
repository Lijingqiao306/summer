-- Spring AI Chat Memory 表定义（覆盖默认 VARCHAR(36)，支持微信 userId）
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(255) NOT NULL,
    sequence_id     INT          NOT NULL,
    content         VARCHAR,
    type            VARCHAR(50),
    timestamp       TIMESTAMP,
    PRIMARY KEY (conversation_id, sequence_id)
);
