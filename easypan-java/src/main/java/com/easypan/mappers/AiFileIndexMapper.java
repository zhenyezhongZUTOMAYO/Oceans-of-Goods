package com.easypan.mappers;

import com.easypan.entity.po.AiFileIndex;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiFileIndexMapper {

    Integer insertOrUpdate(@Param("bean") AiFileIndex aiFileIndex);

    AiFileIndex selectByFileIdAndUserId(@Param("fileId") String fileId, @Param("userId") String userId);

    Integer updateByFileIdAndUserId(@Param("bean") AiFileIndex aiFileIndex, @Param("fileId") String fileId, @Param("userId") String userId);

    List<AiFileIndex> selectByUserId(@Param("userId") String userId);

    Integer deleteByFileIdAndUserId(@Param("fileId") String fileId, @Param("userId") String userId);
}
