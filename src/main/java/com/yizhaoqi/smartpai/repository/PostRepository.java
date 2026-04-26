package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Post> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query(
            value = "SELECT p FROM Post p, User u WHERE u.id = p.userId AND u.schoolTag = :schoolTag ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM Post p, User u WHERE u.id = p.userId AND u.schoolTag = :schoolTag"
    )
    Page<Post> findByAuthorSchoolTag(@Param("schoolTag") String schoolTag, Pageable pageable);

    @Query(
            value = "SELECT p FROM Post p, User u WHERE u.id = p.userId AND u.majorTag = :majorTag ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM Post p, User u WHERE u.id = p.userId AND u.majorTag = :majorTag"
    )
    Page<Post> findByAuthorMajorTag(@Param("majorTag") String majorTag, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Post p set p.likeCount = p.likeCount + 1 where p.id = :id")
    int incrementLikeCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Post p set p.likeCount = case when p.likeCount > 0 then p.likeCount - 1 else 0 end where p.id = :id")
    int decrementLikeCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Post p
               set p.schoolTagSnapshot = :schoolTag,
                   p.collegeTagSnapshot = :collegeTag
             where p.userId = :userId
            """)
    int updateAuthorSchoolCollegeSnapshot(@Param("userId") Long userId,
                                          @Param("schoolTag") String schoolTag,
                                          @Param("collegeTag") String collegeTag);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Post p
               set p.majorTagSnapshot = :majorTag
             where p.userId = :userId
            """)
    int updateAuthorMajorSnapshot(@Param("userId") Long userId,
                                  @Param("majorTag") String majorTag);
}
