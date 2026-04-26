package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "posts", indexes = {
        @Index(name = "idx_posts_created_at", columnList = "created_at"),
        @Index(name = "idx_posts_user_id", columnList = "user_id"),
        @Index(name = "idx_posts_school_tag", columnList = "school_tag_snapshot"),
        @Index(name = "idx_posts_major_tag", columnList = "major_tag_snapshot")
})
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 当前作者的学校/学院/专业快照，存下来加速筛选（避免每次 join users 再 join organization_tags）
    // 用户修改自己标签时不会回刷老帖，这是有意为之——帖子发表时代表"当时的身份"。
    @Column(name = "school_tag_snapshot", length = 255)
    private String schoolTagSnapshot;

    @Column(name = "college_tag_snapshot", length = 255)
    private String collegeTagSnapshot;

    @Column(name = "major_tag_snapshot", length = 255)
    private String majorTagSnapshot;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
