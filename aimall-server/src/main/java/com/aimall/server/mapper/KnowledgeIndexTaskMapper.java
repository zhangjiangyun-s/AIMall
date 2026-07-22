package com.aimall.server.mapper;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeIndexTaskMapper extends BaseMapper<KnowledgeIndexTask> {

    @Select("SELECT * FROM knowledge_index_task WHERE task_id = #{taskId} LIMIT 1 FOR UPDATE")
    KnowledgeIndexTask selectByTaskIdForUpdate(@Param("taskId") String taskId);
}
