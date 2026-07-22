SET @version_publication=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_doc_version' AND column_name='publication_version');
SET @sql=IF(@version_publication=0,'ALTER TABLE knowledge_doc_version ADD COLUMN publication_version varchar(64) DEFAULT NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @version_epoch=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_doc_version' AND column_name='retrieval_epoch');
SET @sql=IF(@version_epoch=0,'ALTER TABLE knowledge_doc_version ADD COLUMN retrieval_epoch bigint NOT NULL DEFAULT 0','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @chunk_publication=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_chunk' AND column_name='publication_version');
SET @sql=IF(@chunk_publication=0,'ALTER TABLE knowledge_chunk ADD COLUMN publication_version varchar(64) DEFAULT NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @chunk_epoch=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_chunk' AND column_name='retrieval_epoch');
SET @sql=IF(@chunk_epoch=0,'ALTER TABLE knowledge_chunk ADD COLUMN retrieval_epoch bigint NOT NULL DEFAULT 0','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @cache_epoch=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='embedding_cache' AND column_name='retrieval_epoch');
SET @sql=IF(@cache_epoch=0,'ALTER TABLE embedding_cache ADD COLUMN retrieval_epoch bigint NOT NULL DEFAULT 0','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS knowledge_retrieval_epoch (
  id tinyint NOT NULL,
  epoch bigint NOT NULL DEFAULT 0,
  updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  CONSTRAINT chk_knowledge_epoch_singleton CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO knowledge_retrieval_epoch(id,epoch) VALUES(1,0);

SET @old_cache_index=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='embedding_cache' AND index_name='uk_embedding_cache_hash_model');
SET @sql=IF(@old_cache_index>0,'ALTER TABLE embedding_cache DROP INDEX uk_embedding_cache_hash_model','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @epoch_cache_index=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='embedding_cache' AND index_name='uk_embedding_cache_hash_model_epoch');
SET @sql=IF(@epoch_cache_index=0,'ALTER TABLE embedding_cache ADD UNIQUE KEY uk_embedding_cache_hash_model_epoch(content_hash,embedding_model,retrieval_epoch)','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE knowledge_doc_version
SET publication_version=COALESCE(publication_version,CONCAT('doc-',doc_id,'-v',version_no))
WHERE status='ACTIVE';
UPDATE knowledge_chunk chunk
JOIN knowledge_doc_version version ON version.id=chunk.doc_version_id
SET chunk.publication_version=version.publication_version,chunk.retrieval_epoch=version.retrieval_epoch
WHERE chunk.status='ACTIVE';
