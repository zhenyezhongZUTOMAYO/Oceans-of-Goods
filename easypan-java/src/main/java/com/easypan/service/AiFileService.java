package com.easypan.service;

import com.easypan.entity.vo.AiFileSummaryVO;
import com.easypan.entity.vo.AiSearchResultVO;
import com.easypan.entity.vo.PaginationResultVO;

public interface AiFileService {

    void asyncParseFile(String fileId, String userId);

    AiFileSummaryVO getAiSummary(String fileId, String userId);

    PaginationResultVO<AiSearchResultVO> search(String userId, String keyword, Integer pageNo, Integer pageSize);
}
