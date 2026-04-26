package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    // 专用于生成浏览器可达的预签名URL，endpoint指向公网地址
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("minioPresignedClient")
    private MinioClient minioPresignedClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    /**
     * 删除文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. MinIO中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);
        
        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));
            
            // 1. 删除Elasticsearch中的数据（必须成功，否则整体回滚避免产生孤儿 chunk）
            elasticsearchService.deleteByFileMd5(fileMd5);
            logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            
            // 2. 删除MinIO中的文件（使用MD5作为对象路径）
            try {
                String objectName = "merged/" + fileUpload.getFileMd5();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从MinIO删除文件: {}", objectName);
            } catch (Exception e) {
                logger.warn("使用MD5路径删除文件失败，尝试使用文件名路径: {}", fileMd5);
                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                try {
                    String oldObjectName = "merged/" + fileUpload.getFileName();
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket("uploads")
                                    .object(oldObjectName)
                                    .build()
                    );
                    logger.info("使用旧路径成功从MinIO删除文件: {}", oldObjectName);
                } catch (Exception ex) {
                    logger.error("从MinIO删除文件时出错（新旧路径都失败）: {}", fileMd5, ex);
                    // 继续删除其他数据
                }
            }
            
            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);
            
            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件，以及其所属组织范围内“组织内公开”的文件（支持层级权限）
     *
     * @param userId 用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);

        try {
            // 兼容传入数据库ID或用户名：先按ID查，再按用户名查
            User user = null;
            try {
                user = userRepository.findById(Long.parseLong(userId)).orElse(null);
            } catch (NumberFormatException ignored) {
                // userId不是数字，按用户名继续
            }
            if (user == null) {
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
            }

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);
            
            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件
                files = fileUploadRepository.findByUserId(userId);
                logger.debug("用户无组织标签，仅返回个人文件");
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(userId, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }
            
            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);
        
        try {
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成用于浏览器内联预览的预签名URL（PDF/图片等由浏览器原生渲染）。
     * 通过覆盖响应头 response-content-disposition=inline 让浏览器在iframe/embed里直接打开，
     * 而不是弹出下载框。后端进程不参与渲染，内存开销为零。
     */
    public String generateInlinePreviewUrl(String fileMd5, String fileName) {
        logger.info("生成内联预览链接: fileMd5={}, fileName={}", fileMd5, fileName);

        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtAsc(fileMd5)
                .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

        String contentType = guessContentType(fileName != null ? fileName : fileUpload.getFileName());
        String disposition = "inline";

        java.util.Map<String, String> extra = new java.util.HashMap<>();
        extra.put("response-content-disposition", disposition);
        extra.put("response-content-type", contentType);

        String objectName = "merged/" + fileMd5;
        try {
            return minioPresignedClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600)
                            .extraQueryParams(extra)
                            .build());
        } catch (Exception e) {
            // 降级：老数据走文件名路径
            String oldObjectName = "merged/" + fileUpload.getFileName();
            try {
                return minioPresignedClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(oldObjectName)
                                .expiry(3600)
                                .extraQueryParams(extra)
                                .build());
            } catch (Exception ex) {
                logger.error("生成内联预览链接失败: fileMd5={}", fileMd5, ex);
                throw new RuntimeException("生成内联预览链接失败: " + ex.getMessage(), ex);
            }
        }
    }

    private String guessContentType(String fileName) {
        String ext = getFileExtension(fileName).toLowerCase();
        switch (ext) {
            case "pdf": return "application/pdf";
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "txt":
            case "md":
            case "log": return "text/plain; charset=utf-8";
            case "html":
            case "htm": return "text/html; charset=utf-8";
            case "json": return "application/json; charset=utf-8";
            case "xml": return "application/xml; charset=utf-8";
            default: return "application/octet-stream";
        }
    }

    /**
     * 生成文件下载链接
     *
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtAsc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 优先使用新的MD5路径
            String objectName = "merged/" + fileMd5;

            try {
                // 尝试使用新路径（MD5）
                String presignedUrl = minioPresignedClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(objectName)
                                .expiry(3600)
                                .build()
                );
                logger.info("成功生成文件下载链接（新路径）: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), objectName);
                return presignedUrl;
            } catch (Exception e) {
                logger.warn("使用新路径生成下载链接失败，尝试使用旧路径（文件名）: fileMd5={}", fileMd5);
                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                String oldObjectName = "merged/" + fileUpload.getFileName();
                String presignedUrl = minioPresignedClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(oldObjectName)
                                .expiry(3600)
                                .build()
                );
                logger.info("成功生成文件下载链接（旧路径）: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), oldObjectName);
                return presignedUrl;
            }
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }
    
    /**
     * 获取文件预览内容
     * 
     * @param fileMd5 文件MD5
     * @param fileName 文件名
     * @return 文件预览内容，对于文本文件返回前几KB内容，非文本文件返回文件信息
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtAsc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 优先使用新的MD5路径
            String objectName = "merged/" + fileMd5;
            InputStream inputStream = null;
            boolean usedNewPath = false;

            try {
                // 尝试使用新路径（MD5）
                inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build());
                usedNewPath = true;
                logger.info("使用新路径（MD5）获取文件流: fileMd5={}, objectName={}", fileMd5, objectName);
            } catch (Exception e) {
                logger.warn("使用新路径获取文件失败，尝试使用旧路径（文件名）: fileMd5={}, error={}", fileMd5, e.getMessage());
                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                String oldObjectName = "merged/" + fileUpload.getFileName();
                inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket("uploads")
                                .object(oldObjectName)
                                .build());
                logger.info("使用旧路径（文件名）获取文件流: fileMd5={}, objectName={}", fileMd5, oldObjectName);
            }

            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isPlainText = isPlainTextFile(fileExtension);
            boolean isTikaParsable = isTikaParsableFile(fileExtension);

            int maxChars = 20000; // 预览最多返回约20K字符

            if (isPlainText) {
                // 对于纯文本文件，按UTF-8读取前若干KB
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = 10240; // 10KB

                    while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes("UTF-8").length + 1;
                    }

                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += "\n... (内容已截断，仅显示前10KB)";
                    }

                    logger.info("成功获取文本文件预览内容: fileMd5={}, 使用MD5路径={}, contentLength={}",
                        fileMd5, usedNewPath, result.length());
                    return result;
                }
            } else if (isTikaParsable) {
                // 对于PDF/DOC/DOCX等二进制文档，用Tika提取纯文本再预览，避免二进制当作UTF-8读出乱码
                try {
                    BodyContentHandler handler = new BodyContentHandler(maxChars);
                    Metadata metadata = new Metadata();
                    AutoDetectParser parser = new AutoDetectParser();
                    parser.parse(inputStream, handler, metadata, new ParseContext());
                    String text = handler.toString();
                    if (text == null || text.isBlank()) {
                        text = String.format("文件名: %s\n文件大小: %s\n文件类型: %s\n\n未能从该文件提取到文本内容，建议下载后查看。",
                            fileName,
                            formatFileSize(fileUpload.getTotalSize()),
                            fileExtension.toUpperCase());
                    } else if (text.length() >= maxChars) {
                        text += "\n... (内容已截断，仅显示前" + maxChars + "字符)";
                    }
                    logger.info("成功通过Tika提取文档预览: fileMd5={}, 扩展名={}, 字符数={}", fileMd5, fileExtension, text.length());
                    return text;
                } catch (org.apache.tika.exception.WriteLimitReachedException wlre) {
                    // 达到字符上限也认为是正常截断
                    logger.info("Tika提取达到预览字符上限: fileMd5={}", fileMd5);
                    return "(预览内容已截断至前" + maxChars + "字符)";
                } catch (Exception tikaEx) {
                    logger.warn("Tika提取文档文本失败，降级为文件信息展示: fileMd5={}, error={}", fileMd5, tikaEx.getMessage());
                    return String.format(
                        "文件名: %s\n文件大小: %s\n文件类型: %s\n\n文档解析失败，请下载后查看。",
                        fileName,
                        formatFileSize(fileUpload.getTotalSize()),
                        fileExtension.toUpperCase());
                } finally {
                    try { inputStream.close(); } catch (Exception ignored) {}
                }
            } else {
                // 对于非文本文件，返回文件信息
                String fileInfo = String.format(
                    "文件名: %s\n" +
                    "文件大小: %s\n" +
                    "文件类型: %s\n" +
                    "上传时间: %s\n\n" +
                    "此文件类型不支持预览，请下载后查看。",
                    fileName,
                    formatFileSize(fileUpload.getTotalSize()),
                    fileExtension.toUpperCase(),
                    fileUpload.getCreatedAt()
                );

                logger.info("返回非文本文件信息: fileMd5={}", fileMd5);
                return fileInfo;
            }

        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
    
    /**
     * 判断是否为纯文本文件（可按UTF-8直接读取）
     */
    private boolean isPlainTextFile(String extension) {
        String[] textExtensions = {
            "txt", "md", "xml", "json", "csv", "log", "java", "js", "ts",
            "py", "cpp", "c", "h", "css", "scss", "less", "sql",
            "yml", "yaml", "properties", "conf", "config", "html", "htm"
        };

        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    /**
     * 判断是否为可由Apache Tika解析为文本的二进制文档（PDF/Word/PPT/Excel等）
     */
    private boolean isTikaParsableFile(String extension) {
        String[] tikaExtensions = {
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "odt", "rtf"
        };

        return Arrays.stream(tikaExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "未知";
        
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
} 
