package com.yizhaoqi.smartpai.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.publicUrl}")
    private String publicUrl;


    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /**
     * 用于生成浏览器可直接访问的预签名URL：
     * 必须使用对外可达的 S3 API 地址，否则浏览器会拿到 Docker 内部主机名（如 http://minio:19001）。
     * 预签名签名包含 Host，因此必须用一个 endpoint 指向 publicUrl 的独立客户端来生成。
     */
    @Bean(name = "minioPresignedClient")
    public MinioClient minioPresignedClient() {
        // 必须显式指定 region：否则生成预签名URL时 SDK 会先回源调用 getBucketLocation 探测区域，
        // 从容器内访问宿主机公网IP常因NAT/防火墙阻断而挂起，最终触发前端 10s 超时。
        return MinioClient.builder()
                .endpoint(publicUrl)
                .region("us-east-1")
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }
}
