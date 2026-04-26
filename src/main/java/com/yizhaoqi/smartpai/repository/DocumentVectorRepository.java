package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块
    
    /**
     * 删除指定文件MD5的所有文档向量记录
     * 
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)// ？1表示匹配第一个参数
    void deleteByFileMd5(String fileMd5);

    @Transactional
    @Modifying
    @Query(value = "UPDATE document_vectors SET org_tag = ?2, is_public = ?3 WHERE file_md5 = ?1", nativeQuery = true)
    int updateOrgTagByFileMd5(String fileMd5, String orgTag, boolean isPublic);
}
