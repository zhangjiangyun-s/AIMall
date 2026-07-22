package com.aimall.server.mapper;

import com.aimall.server.entity.KnowledgeChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    @Update("""
            UPDATE knowledge_chunk
            SET vector_delete_retry_count = vector_delete_retry_count + 1,
                embedding_sync_status = CASE
                    WHEN vector_delete_retry_count + 1 >= #{maxRetry} THEN 'DELETE_DEAD'
                    ELSE 'DELETE_PENDING'
                END,
                vector_delete_next_retry_at = CASE
                    WHEN vector_delete_retry_count + 1 >= #{maxRetry} THEN NULL
                    ELSE DATE_ADD(NOW(), INTERVAL LEAST(300, 5 * POW(2, vector_delete_retry_count)) SECOND)
                END,
                vector_delete_last_error = LEFT(#{errorMessage}, 1000),
                vector_delete_claim_token = NULL,
                vector_delete_claim_until = NULL,
                updated_at = NOW()
            WHERE id = #{chunkId}
              AND embedding_sync_status = 'DELETE_PROCESSING'
              AND vector_delete_claim_token = #{claimToken}
            """)
    int failVectorDeletion(
            @Param("chunkId") Long chunkId,
            @Param("claimToken") String claimToken,
            @Param("errorMessage") String errorMessage,
            @Param("maxRetry") int maxRetry
    );
}
