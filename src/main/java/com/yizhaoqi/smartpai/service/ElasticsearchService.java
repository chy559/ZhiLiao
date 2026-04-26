package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;

import java.util.HashMap;
import java.util.Map;
import com.yizhaoqi.smartpai.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    /**
     * 批量索引文档到Elasticsearch中
     * 通过接收一个EsDocument对象列表，将这些文档批量索引到名为"knowledge_base"的索引中
     * 使用Elasticsearch的Bulk API来执行批量索引操作，以提高索引效率
     *
     * @param documents 文档列表，每个文档都将被索引到Elasticsearch中
     */
    public void bulkIndex(List<EsDocument> documents) {
        try {
            logger.info("开始批量索引文档到Elasticsearch，文档数量: {}", documents.size());
            
            // 将文档列表转换为批量操作列表，每个文档都对应一个索引操作
            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index("knowledge_base") // 指定索引名称
                            .id(doc.getId()) // 使用文档的ID作为Elasticsearch中的文档ID
                            .document(doc) // 将文档对象作为数据源
                    )))
                    .toList();

            // 创建BulkRequest对象，并将批量操作列表添加到请求中
            BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));
            
            // 执行批量索引操作
            BulkResponse response = esClient.bulk(request);
            
            // 检查响应结果
            if (response.errors()) {
                logger.error("批量索引过程中发生错误:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("批量索引部分失败，请检查日志");
            } else {
                logger.info("批量索引成功完成，文档数量: {}", documents.size());
            }
        } catch (Exception e) {
            logger.error("批量索引失败，文档数量: {}", documents.size(), e);
            // 如果发生异常，抛出运行时异常，表明批量索引失败
            throw new RuntimeException("批量索引失败", e);
        }
    }

    /**
     * 将某个文件在索引里的所有分块的 orgTag / isPublic 原地改写。
     * 用于用户修改学校或学院后，同步迁移已有的"组织内公开"资料。
     */
    public void updateOrgTagByFileMd5(String fileMd5, String newOrgTag, boolean isPublic) {
        try {
            Map<String, JsonData> params = new HashMap<>();
            params.put("orgTag", JsonData.of(newOrgTag));
            params.put("isPublic", JsonData.of(isPublic));

            Script script = Script.of(s -> s.inline(i -> i
                    .source("ctx._source.orgTag = params.orgTag; ctx._source.isPublic = params.isPublic")
                    .lang("painless")
                    .params(params)));

            Query query = Query.of(q -> q.term(t -> t.field("fileMd5").value(fileMd5)));

            UpdateByQueryRequest request = UpdateByQueryRequest.of(u -> u
                    .index("knowledge_base")
                    .query(query)
                    .script(script)
                    .refresh(true)
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));

            UpdateByQueryResponse response = esClient.updateByQuery(request);
            logger.info("ES 分块 orgTag 迁移完成 => fileMd5: {}, newOrgTag: {}, isPublic: {}, updated: {}",
                    fileMd5, newOrgTag, isPublic, response.updated());
        } catch (Exception e) {
            logger.error("ES 分块 orgTag 迁移失败 => fileMd5: {}, newOrgTag: {}", fileMd5, newOrgTag, e);
            throw new RuntimeException("迁移 ES 文档 orgTag 失败", e);
        }
    }

    /**
     * 根据file_md5删除文档
     * @param fileMd5 文件指纹
     */
    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index("knowledge_base")
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
                    .refresh(true)
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            );
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }

    /**
     * 清理孤儿 chunk：删除 knowledge_base 中 fileMd5 不在 validMd5s 里的全部文档。
     * 历史上 delete 流程的 ES 删除在某些失败场景被吞掉，导致 file_upload 被删、ES chunk 残留。
     * @param validMd5s 当前 file_upload 表里仍然存在的全部 fileMd5
     * @return 被删除的 chunk 数量
     */
    public long deleteOrphansNotIn(java.util.Collection<String> validMd5s) {
        try {
            Query query;
            if (validMd5s == null || validMd5s.isEmpty()) {
                query = Query.of(q -> q.matchAll(m -> m));
            } else {
                List<co.elastic.clients.elasticsearch._types.FieldValue> values =
                        validMd5s.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .toList();
                query = Query.of(q -> q.bool(b -> b.mustNot(mn -> mn.terms(
                        t -> t.field("fileMd5").terms(tv -> tv.value(values))))));
            }

            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index("knowledge_base")
                    .query(query)
                    .refresh(true)
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));

            Long deleted = esClient.deleteByQuery(request).deleted();
            long count = deleted == null ? 0L : deleted;
            logger.info("ES 孤儿 chunk 清理完成，删除条数: {}, 白名单大小: {}", count,
                    validMd5s == null ? 0 : validMd5s.size());
            return count;
        } catch (Exception e) {
            logger.error("清理 ES 孤儿 chunk 失败", e);
            throw new RuntimeException("清理 ES 孤儿数据失败", e);
        }
    }
}
