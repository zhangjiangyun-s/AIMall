package com.aimall.server.mapper;

import com.aimall.server.entity.AiActionExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiActionExecutionMapper extends BaseMapper<AiActionExecution> {

    @Update("""
            UPDATE ai_action_execution
            SET status = 'RECOVERY_REQUIRED',
                error_message = '执行超时，需核对业务结果',
                updated_at = NOW()
            WHERE status = 'PROCESSING'
              AND updated_at <= DATE_SUB(NOW(), INTERVAL 5 MINUTE)
            """)
    int markStaleForRecovery();
}
