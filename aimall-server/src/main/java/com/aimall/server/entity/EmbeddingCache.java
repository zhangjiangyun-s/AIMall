package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("embedding_cache")
public class EmbeddingCache {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contentHash;
    private String embeddingModel;
    private Long retrievalEpoch;
    private Integer vectorDimension;
    private String embeddingId;
    private Integer hitCount;
    private LocalDateTime createdAt;
    private LocalDateTime expiredAt;
}
