package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findByFileMd5(String fileMd5);

    // 同一文件可能被多个用户上传，返回最早一条用于路径回退等无所有权要求的查询
    Optional<FileUpload> findFirstByFileMd5OrderByCreatedAtAsc(String fileMd5);

    Optional<FileUpload> findByFileMd5AndUserId(String fileMd5, String userId);

    Optional<FileUpload> findByFileMd5AndIsPublicTrue(String fileMd5);

    Optional<FileUpload> findByFileNameAndIsPublicTrue(String fileName);
    
    long countByFileMd5(String fileMd5);
    
    void deleteByFileMd5(String fileMd5);
    
    void deleteByFileMd5AndUserId(String fileMd5, String userId);

    @Query("SELECT DISTINCT f.fileMd5 FROM FileUpload f")
    List<String> findAllDistinctFileMd5();

    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）
     * 包括：1. 用户自己上传的文件
     *      2. 组织内公开的文件（组织标签命中且 isPublic=true）
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR (f.orgTag IN :orgTagList AND f.isPublic = true)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户可访问的所有文件（原始方法，保留向后兼容性）
     * 
     * @param userId 用户ID
     * @param orgTagList 用户所属的组织标签列表（逗号分隔）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR (f.orgTag IN :orgTagList AND f.isPublic = true)")
    List<FileUpload> findAccessibleFiles(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByFileMd5In(List<String> md5List);

    List<FileUpload> findByFileMd5InAndIsPublicTrue(List<String> md5List);

    @Query("SELECT f FROM FileUpload f WHERE f.fileMd5 IN :md5List AND f.userId = :userId")
    List<FileUpload> findVisibleFilesByMd5WithoutOrg(@Param("md5List") List<String> md5List, @Param("userId") String userId);

    @Query("SELECT f FROM FileUpload f WHERE f.fileMd5 IN :md5List AND (f.userId = :userId OR (f.orgTag IN :orgTagList AND f.isPublic = true))")
    List<FileUpload> findVisibleFilesByMd5(@Param("md5List") List<String> md5List,
                                           @Param("userId") String userId,
                                           @Param("orgTagList") List<String> orgTagList);
}
