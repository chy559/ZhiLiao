package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    Optional<PostLike> findByPostIdAndUserId(Long postId, Long userId);

    List<PostLike> findByUserIdAndPostIdIn(Long userId, List<Long> postIds);

    void deleteByPostId(Long postId);

    void deleteByUserId(Long userId);
}
