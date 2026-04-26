package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "org_tags")
    private String orgTags; // 用户所属组织标签，多个用逗号分隔

    @Column(name = "primary_org")
    private String primaryOrg; // 用户主组织标签

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "email", unique = true, length = 128)
    private String email;

    // 学校标签 id，引用 OrganizationTag 里 type=SCHOOL 的行；可为空
    @Column(name = "school_tag", length = 255)
    private String schoolTag;

    // 学院标签 id，引用 OrganizationTag 里 type=COLLEGE 且 parentTag=schoolTag 的行；可为空
    @Column(name = "college_tag", length = 255)
    private String collegeTag;

    // 专业标签 id，引用 OrganizationTag 里 type=MAJOR 的行；跨校共享（比如 MAJOR_CS 代表"计算机"）；可为空
    @Column(name = "major_tag", length = 255)
    private String majorTag;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Role {
        USER, ADMIN
    }
}
