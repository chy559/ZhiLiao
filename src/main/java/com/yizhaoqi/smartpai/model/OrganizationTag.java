package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "organization_tags")
public class OrganizationTag {
    @Id
    @Column(name = "tag_id")
    private String tagId; // 标签唯一标识

    @Column(nullable = false)
    private String name; // 标签名称

    @Column(columnDefinition = "TEXT")
    private String description; // 描述

    @Column(name = "parent_tag", length = 255)
    private String parentTag; // 父标签ID

    /**
     * 标签类型：SCHOOL=学校 / COLLEGE=学院 / OTHER=其它（默认、私人空间等）。
     * 前端在"选学校/学院"下拉里只读取 SCHOOL / COLLEGE，避免把 DEFAULT / PRIVATE_* 混进去。
     * 旧数据字段为 null，当作 OTHER 处理。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16)
    private Type type;

    public enum Type {
        SCHOOL, COLLEGE, MAJOR, OTHER
    }

    @ManyToOne
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy; // 创建者ID

    @CreationTimestamp
    private LocalDateTime createdAt; // 创建时间

    @UpdateTimestamp
    private LocalDateTime updatedAt; // 更新时间
} 