package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @Autowired
    private HybridSearchService hybridSearchService;

    @GetMapping("/hybrid")
    public Map<String, Object> hybridSearch(@RequestParam String query,
                                            @RequestParam(defaultValue = "10") int topK,
                                            @RequestAttribute(value = "userId", required = false) String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("HYBRID_SEARCH");
        try {
            LogUtils.logBusiness("HYBRID_SEARCH", userId != null ? userId : "anonymous",
                    "开始混合检索: query=%s, topK=%d", query, topK);

            List<SearchResult> results = userId != null
                    ? hybridSearchService.searchWithPermission(query, userId, topK)
                    : hybridSearchService.searchPublic(query, topK);

            LogUtils.logUserOperation(userId != null ? userId : "anonymous", "HYBRID_SEARCH",
                    "search_query", "SUCCESS");
            LogUtils.logBusiness("HYBRID_SEARCH", userId != null ? userId : "anonymous",
                    "混合检索完成: resultCount=%d", results.size());
            monitor.end("混合检索成功");

            Map<String, Object> responseBody = new HashMap<>(4);
            responseBody.put("code", 200);
            responseBody.put("message", "success");
            responseBody.put("data", results);
            return responseBody;
        } catch (Exception e) {
            LogUtils.logBusinessError("HYBRID_SEARCH", userId != null ? userId : "anonymous",
                    "混合检索失败: query=%s", e, query);
            monitor.end("混合检索失败: " + e.getMessage());

            Map<String, Object> errorBody = new HashMap<>(4);
            errorBody.put("code", 500);
            errorBody.put("message", e.getMessage());
            errorBody.put("data", Collections.emptyList());
            return errorBody;
        }
    }
}
