SET @has_source_trust_score = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_doc'
      AND column_name = 'source_trust_score'
);
SET @sql = IF(
    @has_source_trust_score = 0,
    'ALTER TABLE knowledge_doc ADD COLUMN source_trust_score DECIMAL(4,3) NOT NULL DEFAULT 0.500 AFTER source_system',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
