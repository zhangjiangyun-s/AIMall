package com.aimall.server.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeRetrievalEpochMapper {
    @Select("SELECT epoch FROM knowledge_retrieval_epoch WHERE id=1 FOR UPDATE")
    Long lockCurrent();

    @Update("UPDATE knowledge_retrieval_epoch SET epoch=epoch+1 WHERE id=1 AND epoch=#{expected}")
    int advance(@Param("expected") long expected);

    @Select("SELECT epoch FROM knowledge_retrieval_epoch WHERE id=1")
    Long current();
}
