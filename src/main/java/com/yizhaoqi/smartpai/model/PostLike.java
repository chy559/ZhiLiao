package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "post_likes",
        uniqueConstraints = @UniqueConstraint(name = "uk_post_user", columnNames = {"post_id", "user_id"}),
        indexes = {
                @Index(name = "idx_post_likes_user_id", columnList = "user_id"),
                @Index(name = "idx_post_likes_post_id", columnList = "post_id")
        })
public class PostLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
