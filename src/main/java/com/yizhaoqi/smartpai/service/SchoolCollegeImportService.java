package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 学校/学院 批量导入服务。
 *
 * 1. 应用启动时，如果数据库里没有任何 type=SCHOOL 的标签，则从 classpath:seed-schools.csv 自动导入。
 *    —— 只看 SCHOOL 的数量是否为 0，避免已有用户的站点在升级时被二次 seed。
 * 2. 管理员后台也能上传同格式 CSV，走 {@link #importFromCsv} 做增量 upsert。
 *
 * CSV 列：type,tagId,name,parentTagId,description
 *   - 以 # 开头的行 / 空行 会被忽略
 *   - 同 tagId 存在时：更新 name / description / parentTag / type（不碰 createdBy / createdAt）
 */
@Service
public class SchoolCollegeImportService {

    private static final Logger logger = LoggerFactory.getLogger(SchoolCollegeImportService.class);
    private static final String SEED_PATH = "seed-schools.csv";
    private static final String SEED_MAJORS_PATH = "seed-majors.csv";
    private static final String SYSTEM_ADMIN_USERNAME = "admin";

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * 启动时自动 seed。放 @PostConstruct 里：JPA DDL 已经建表，可以安全查询 / 插入。
     */
    @PostConstruct
    public void seedIfEmpty() {
        seedFromClassPath(SEED_PATH, OrganizationTag.Type.SCHOOL, "schools/colleges");
        seedFromClassPath(SEED_MAJORS_PATH, OrganizationTag.Type.MAJOR, "majors");
    }

    private void seedFromClassPath(String path, OrganizationTag.Type gateType, String label) {
        try {
            if (organizationTagRepository.countByType(gateType) > 0) {
                logger.info("{} tags already seeded, skip {}.", gateType, path);
                return;
            }
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                logger.warn("{} not found on classpath, skip {} seeding.", path, label);
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                ImportResult result = importFromCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8), null);
                logger.info("Seeded {} from {}: inserted={}, updated={}, failed={}",
                        label, path, result.inserted, result.updated, result.failed);
            }
        } catch (Exception e) {
            logger.error("Seed {} failed, continue without seed.", label, e);
        }
    }

    /**
     * 从 CSV 文本导入学校/学院。幂等：同 tagId 会被更新而非重复插入。
     *
     * @param csv         CSV 全文
     * @param operator    执行人（管理员 username）；seed 场景传 null，会尝试用 admin 用户，再不行就用任意已存在用户
     * @return 统计结果
     */
    @Transactional
    public ImportResult importFromCsv(String csv, String operator) {
        ImportResult result = new ImportResult();
        if (csv == null || csv.isBlank()) {
            return result;
        }

        User creator = resolveCreator(operator);
        if (creator == null) {
            // 连一个用户都没有，就跳过；等第一个管理员注册后再 seed
            logger.warn("No user available to own seeded tags, skip import.");
            return result;
        }

        // 两轮处理：先处理所有 SCHOOL，再处理 COLLEGE —— 避免 COLLEGE 引用还没写入的 SCHOOL parent
        List<String[]> collegeRows = new ArrayList<>();
        Map<String, String> errors = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(csv))) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] cols = splitCsvLine(trimmed);
                if (cols.length < 3) {
                    result.failed++;
                    continue;
                }
                // 第一行是表头（type,tagId,name,...）就跳过；只识别一次
                if (!headerSkipped && "type".equalsIgnoreCase(cols[0].trim())) {
                    headerSkipped = true;
                    continue;
                }
                String type = cols[0].trim().toUpperCase();
                if ("SCHOOL".equals(type) || "MAJOR".equals(type)) {
                    try {
                        OrganizationTag.Type t = "SCHOOL".equals(type)
                                ? OrganizationTag.Type.SCHOOL
                                : OrganizationTag.Type.MAJOR;
                        upsert(cols, t, creator, result);
                    } catch (Exception e) {
                        result.failed++;
                        errors.put(cols.length > 1 ? cols[1] : "(?)", e.getMessage());
                    }
                } else if ("COLLEGE".equals(type)) {
                    collegeRows.add(cols);
                } else {
                    // 未知类型行忽略（比如表头被注释掉后还有空白列）
                    result.failed++;
                }
            }
        } catch (Exception e) {
            logger.error("Read csv failed: {}", e.getMessage(), e);
        }

        // 第二轮：COLLEGE
        for (String[] cols : collegeRows) {
            try {
                upsert(cols, OrganizationTag.Type.COLLEGE, creator, result);
            } catch (Exception e) {
                result.failed++;
                errors.put(cols.length > 1 ? cols[1] : "(?)", e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            logger.warn("Import had {} row errors, samples: {}", errors.size(),
                    errors.entrySet().stream().limit(5).toList());
        }
        return result;
    }

    /**
     * 选择导入行的 createdBy：管理员 → 参数 username → 任意已存在用户。
     * 老的 createOrganizationTag 里 createdBy 是 NOT NULL，不能给空。
     */
    private User resolveCreator(String operator) {
        if (operator != null && !operator.isBlank()) {
            Optional<User> u = userRepository.findByUsername(operator);
            if (u.isPresent()) return u.get();
        }
        Optional<User> admin = userRepository.findByUsername(SYSTEM_ADMIN_USERNAME);
        if (admin.isPresent()) return admin.get();
        return userRepository.findAll().stream().findFirst().orElse(null);
    }

    private void upsert(String[] cols, OrganizationTag.Type type, User creator, ImportResult result) {
        String tagId = safe(cols, 1);
        String name = safe(cols, 2);
        String parentTag = safe(cols, 3);
        String description = safe(cols, 4);

        if (tagId.isEmpty() || name.isEmpty()) {
            result.failed++;
            return;
        }
        if (type == OrganizationTag.Type.COLLEGE && parentTag.isEmpty()) {
            result.failed++;
            return;
        }

        Optional<OrganizationTag> existing = organizationTagRepository.findByTagId(tagId);
        OrganizationTag tag = existing.orElseGet(OrganizationTag::new);
        tag.setTagId(tagId);
        tag.setName(name);
        tag.setDescription(description);
        tag.setParentTag(parentTag.isEmpty() ? null : parentTag);
        tag.setType(type);
        if (tag.getCreatedBy() == null) {
            tag.setCreatedBy(creator);
        }
        organizationTagRepository.save(tag);
        if (existing.isPresent()) {
            result.updated++;
        } else {
            result.inserted++;
        }
    }

    /**
     * 简单的 CSV 行拆分：支持双引号包裹的值，用于 name/description 里含逗号的场景。
     */
    static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    private static String safe(String[] cols, int idx) {
        if (idx >= cols.length) return "";
        String v = cols[idx];
        return v == null ? "" : v.trim();
    }

    public static class ImportResult {
        public int inserted;
        public int updated;
        public int failed;
    }
}
