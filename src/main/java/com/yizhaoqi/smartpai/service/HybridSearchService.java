package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("Start permission search, query: {}, userId: {}", query, userId);

        try {
            String ownerId = resolveSearchOwnerId(userId);
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            List<FileUpload> accessibleFiles = getAccessibleFiles(ownerId, userEffectiveTags);
            Set<String> accessibleMd5Set = accessibleFiles.stream()
                    .map(FileUpload::getFileMd5)
                    .collect(Collectors.toCollection(HashSet::new));

            final List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                logger.warn("Embedding failed, fallback to text-only permission search");
                return textOnlySearchWithPermission(query, ownerId, userEffectiveTags, accessibleMd5Set, topK);
            }

            int recallK = topK * 30;
            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK));
                        s.query(q -> q.bool(b -> b
                                .must(m -> m.match(mm -> mm.field("textContent").query(query)))));
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)))));
                        s.size(recallK);
                        return s;
                    }, EsDocument.class);

            List<SearchResult> results = filterResultsByAccessibleMd5(mapResults(response), accessibleMd5Set, topK);
            attachFileNames(results, ownerId, userEffectiveTags);
            return results;
        } catch (Exception e) {
            logger.error("Permission search failed", e);
            try {
                String ownerId = resolveSearchOwnerId(userId);
                List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
                List<FileUpload> accessibleFiles = getAccessibleFiles(ownerId, userEffectiveTags);
                Set<String> accessibleMd5Set = accessibleFiles.stream()
                        .map(FileUpload::getFileMd5)
                        .collect(Collectors.toCollection(HashSet::new));
                return textOnlySearchWithPermission(query, ownerId, userEffectiveTags, accessibleMd5Set, topK);
            } catch (Exception fallbackError) {
                logger.error("Permission search fallback failed", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    private List<SearchResult> textOnlySearchWithPermission(
            String query,
            String ownerId,
            List<String> userEffectiveTags,
            Set<String> accessibleMd5Set,
            int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(mm -> mm.field("textContent").query(query)))))
                    .minScore(0.3d)
                    .size(topK * 30), EsDocument.class);

            List<SearchResult> results = filterResultsByAccessibleMd5(mapResults(response), accessibleMd5Set, topK);
            attachFileNames(results, ownerId, userEffectiveTags);
            return results;
        } catch (Exception e) {
            logger.error("Text-only permission search failed", e);
            return new ArrayList<>();
        }
    }

    public List<SearchResult> searchPublic(String query, int topK) {
        return Collections.emptyList();
    }

    @Deprecated
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.warn("Using unscoped search() — no permission filter, should migrate to searchWithPermission()");
            final List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                return textOnlySearch(query, topK);
            }

            int recallK = topK * 30;
            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK));
                        s.query(q -> q.match(m -> m.field("textContent").query(query)));
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)))));
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score());
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("Unscoped search failed", e);
            try {
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                throw new RuntimeException("Search failed completely", fallbackError);
            }
        }
    }

    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q.match(m -> m.field("textContent").query(query)))
                .size(topK), EsDocument.class);

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score());
                })
                .toList();
    }

    private List<SearchResult> textOnlySearchPublic(String query, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(mm -> mm.field("textContent").query(query)))
                            .filter(f -> f.term(t -> t.field("isPublic").value(true)))))
                    .size(topK), EsDocument.class);

            List<SearchResult> results = mapResults(response);
            attachFileNames(results, null, Collections.emptyList());
            return results;
        } catch (Exception e) {
            logger.error("Text-only public search failed", e);
            return new ArrayList<>();
        }
    }

    private List<SearchResult> mapResults(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            hit.source().getUserId(),
                            hit.source().getOrgTag(),
                            hit.source().isPublic());
                })
                .toList();
    }

    private Query buildPermissionFilter(String ownerId, List<String> userEffectiveTags) {
        BoolQuery.Builder permission = new BoolQuery.Builder();
        permission.minimumShouldMatch("1");
        permission.should(s -> s.term(t -> t.field("userId").value(ownerId)));

        if (userEffectiveTags == null || userEffectiveTags.isEmpty()) {
            permission.should(s -> s.matchNone(mn -> mn));
        } else {
            for (String tag : userEffectiveTags) {
                permission.should(s -> s.bool(b -> b
                        .must(m -> m.term(t -> t.field("orgTag").value(tag)))
                        .must(m -> m.term(t -> t.field("isPublic").value(true)))));
            }
        }

        return new Query.Builder().bool(permission.build()).build();
    }

    private List<FileUpload> getAccessibleFiles(String ownerId, List<String> userEffectiveTags) {
        if (userEffectiveTags == null || userEffectiveTags.isEmpty()) {
            return fileUploadRepository.findByUserId(ownerId);
        }
        return fileUploadRepository.findAccessibleFilesWithTags(ownerId, userEffectiveTags);
    }

    private List<SearchResult> filterResultsByAccessibleMd5(List<SearchResult> results, Set<String> accessibleMd5Set, int topK) {
        if (results == null || results.isEmpty() || accessibleMd5Set == null || accessibleMd5Set.isEmpty()) {
            return Collections.emptyList();
        }

        return results.stream()
                .filter(result -> accessibleMd5Set.contains(result.getFileMd5()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("Embedding result is empty");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("Embedding failed", e);
            return null;
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = findUser(userId);
            List<String> rawTags = parseRawOrgTags(user.getOrgTags());
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            if (effectiveTags == null) {
                effectiveTags = Collections.emptyList();
            }

            // 登录后如果用户从未触发过 org-tag 读取，缓存会被错误地落地成只有 DEFAULT。
            // 只要数据库里实际持有的标签没有全部出现在缓存里，就按 DB 重建两份缓存。
            if (!rawTags.isEmpty() && !effectiveTags.containsAll(rawTags)) {
                logger.info("Effective org-tag cache stale for user {}, rebuilding from DB. cache={}, raw={}",
                        user.getUsername(), effectiveTags, rawTags);
                List<String> refreshed = orgTagCacheService.refreshUserOrgTagCaches(user.getUsername(), rawTags);
                if (refreshed != null && !refreshed.isEmpty()) {
                    effectiveTags = refreshed;
                }
            }
            return effectiveTags;
        } catch (Exception e) {
            logger.error("Get effective org tags failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<String> parseRawOrgTags(String orgTags) {
        if (orgTags == null || orgTags.isBlank()) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(orgTags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    String resolveSearchOwnerId(String userId) {
        return findUser(userId).getId().toString();
    }

    private User findUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private void attachFileNames(List<SearchResult> results, String ownerId, List<String> userEffectiveTags) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            List<String> md5List = new ArrayList<>(results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet()));
            List<FileUpload> uploads;
            if (ownerId == null) {
                uploads = Collections.emptyList();
            } else if (userEffectiveTags == null || userEffectiveTags.isEmpty()) {
                uploads = fileUploadRepository.findVisibleFilesByMd5WithoutOrg(md5List, ownerId);
            } else {
                uploads = fileUploadRepository.findVisibleFilesByMd5(md5List, ownerId, userEffectiveTags);
            }

            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(
                            FileUpload::getFileMd5,
                            FileUpload::getFileName,
                            (left, right) -> left,
                            LinkedHashMap::new));

            results.forEach(result -> result.setFileName(md5ToName.get(result.getFileMd5())));
        } catch (Exception e) {
            logger.error("Attach file names failed", e);
        }
    }
}
