package com.easypan.mappers;

import com.easypan.entity.po.AiFileChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiFileChunkMapper {

    Integer insertBatch(@Param("list") List<AiFileChunk> list);

    Integer deleteByFileIdAndUserId(@Param("fileId") String fileId, @Param("userId") String userId);

    List<AiFileChunk> selectByUserId(@Param("userId") String userId);
}
