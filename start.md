# Docker 启动与关闭

## 日常最短启动

### 情况一：Docker 依赖已经关闭

在第一个终端执行：

```bash
cd ~/projects/ZhiLiao-wsl
docker compose -f docs/docker-compose-1.yaml up -d
mvn spring-boot:run -Dspring-boot.run.profiles=docker-local
```

在第二个终端执行：

```bash
cd ~/projects/ZhiLiao-wsl/frontend
pnpm dev
```

打开前端：

```text
http://localhost:9527
```

### 情况二：Docker 依赖已经在运行

在第一个终端执行：

```bash
cd ~/projects/ZhiLiao-wsl
mvn spring-boot:run -Dspring-boot.run.profiles=docker-local
```

在第二个终端执行：

```bash
cd ~/projects/ZhiLiao-wsl/frontend
pnpm dev
```

## 关闭 Docker 依赖

### 彻底关闭并移除容器

```bash
cd ~/projects/ZhiLiao-wsl
docker compose -f docs/docker-compose-1.yaml down
```

### 仅暂停容器，不删除容器

```bash
cd ~/projects/ZhiLiao-wsl
docker compose -f docs/docker-compose-1.yaml stop
```

## 选择建议

- 平时开发结束，优先使用 `stop`
- 想彻底清理这套容器时，再使用 `down`




1.
mysql: [Warning] Using a password on the command line interface can be insecure.
+----+----------+-------------+-----------------+-------------------------------------------------------------------------------------------------------------------------------------------------------------+
| id | username | school_tag  | college_tag     | org_tags                                                                                                                                                    |
+----+----------+-------------+-----------------+-------------------------------------------------------------------------------------------------------------------------------------------------------------+
|  3 | 67677    | SCHOOL_HUST | COLLEGE_HUST_CS | MAJOR_CS,COLLEGE_HUST_TJ,PRIVATE_67677,COLLEGE_HUST_EI,SCHOOL_HUST,COLLEGE_HUST_CS                                                                          |
|  4 | kuoz     | SCHOOL_HUST | COLLEGE_HUST_CS | PRIVATE_kuoz,COLLEGE_SJTU_CS,COLLEGE_SJTU_EE,COLLEGE_SJTU_SOFTWARE,COLLEGE_SJTU_MED,COLLEGE_SJTU_ACEM,COLLEGE_SJTU_ME,MAJOR_FIN,SCHOOL_HUST,COLLEGE_HUST_CS |
+----+----------+-------------+-----------------+-------------------------------------------------------------------------------------------------------------------------------------------------------------+

2.
用docker exec -it mysql mysql -uroot -pPaiSmart2025 -D PaiSmart -e "SELECT id, file_md5, file_name, user_id, org_tag, is_public, status, created_at FROM file_upload WHERE file_name LIKE '%文件系统%' OR file_name LIKE '%基础实验%' ORDER BY created_at DESC;"查询的结果是mysql: [Warning] Using a password on the command line interface can be insecure.

3.mysql: [Warning] Using a password on the command line interface can be insecure.

curl -u elastic:PaiSmart2025 -s "http://127.0.0.1:9200/knowledge_base/_search" \
  -H 'Content-Type: application/json' -d '{
    "_source": ["fileMd5","chunkId","fileName","userId","orgTag","isPublic"],
    "query": {
      "terms": {
        "fileMd5": [
          "41f2e4f3f594da076a407c7a2265671a",
          "0722f7273379b51b53139708b5e82d80"
        ]
      }
    },
    "size": 20
  }'


+----+----------------------------------+--------------+---------+-----------------+----------------------+--------+----------------------------+
| id | file_md5                         | file_name    | user_id | org_tag         | is_public            | status | created_at                 |
+----+----------------------------------+--------------+---------+-----------------+----------------------+--------+----------------------------+
| 24 | 41f2e4f3f594da076a407c7a2265671a | ??4?????.pdf | 4       | COLLEGE_HUST_CS | 0x01                 |      1 | 2026-04-21 21:22:30.397064 |
| 25 | 0722f7273379b51b53139708b5e82d80 | ????1,2.pdf  | 3       | COLLEGE_HUST_CS | 0x01                 |      1 | 2026-04-21 22:03:59.866308 |
+----+----------------------------------+--------------+---------+-----------------+----------------------+--------+----------------------------+

+----------------------------------+---------+-----------------+----------------------+-----+
| file_md5                         | user_id | org_tag         | is_public            | cnt |
+----------------------------------+---------+-----------------+----------------------+-----+
| 41f2e4f3f594da076a407c7a2265671a | 4       | COLLEGE_HUST_CS | 0x01                 | 241 |
| 0722f7273379b51b53139708b5e82d80 | 3       | COLLEGE_HUST_CS | 0x01                 |  42 |
+----------------------------------+---------+-----------------+----------------------+-----+

root@iZn4ahu4v42sjb61g5ixceZ:/opt/pai-smart# curl -u elastic:PaiSmart2025 -s "http://127.0.0.1:9200/knowledge_base/_search" -H 'Content-Type: application/json' -d '{"_source":["fileMd5","chunkId","userId","orgTag","isPublic"],"query":{"term":{"fileMd5":"0722f7273379b51b53139708b5e82d80"}},"size":10}'
{"took":7,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},"hits":{"total":{"value":42,"relation":"eq"},"max_score":1.8994701,"hits":[{"_index":"knowledge_base","_id":"b055898b-ec29-425c-815f-5da7c55e1fe7","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":1,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"dc959abe-52fc-442c-ba80-e6c646e196b1","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":2,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"5f4ef149-7ac1-4ebf-b857-342431eb3313","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":3,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"a7392622-7d08-4e19-9aa0-39e7519b2d6b","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":4,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"df257c32-870b-4dd8-8fcd-84e06360dac1","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":5,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"7e6bfb46-44d6-4e83-867b-871ad8ffaa02","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":6,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"4908a073-60c8-4f34-adb8-52b5c3588197","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":7,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"422208eb-dd3e-4ef5-a0f5-99b3a00904a5","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":8,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"2295cc7c-2ac8-4007-a607-7f95375e3b40","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":9,"userId":"3","orgTag":"COLLEGE_HUST_CS"}},{"_index":"knowledge_base","_id":"d0391152-7ed8-4052-9426-280ac11883ec","_score":1.8994701,"_source":{"fileMd5":"0722f7273379b51b53139708b5e82d80","chunkId":10,"userId":"3","orgTag":"COLLEGE_HUST_CS"}}]}}root@iZn4ahu4v42sjb61g5ixceZ:

/opt/pai-smart# curl -u elastic:PaiSmart2025 curl -u elastic:PaiSmart2025 -s "http://127.0.0.1:9200/knowledge_base/_count" -H 'Content-Type: application/json' -d '{"query":{"term":{"fileMd5":"0722f7273379b51b53139708b5e82d80"}}}'
{"count":42,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0}}root@iZn4ahu4v42sjb61g5ixceZ:/opt/pai-smart# 