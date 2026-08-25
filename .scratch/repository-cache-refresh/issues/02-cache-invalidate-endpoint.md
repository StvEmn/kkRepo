# 02 — 缓存失效 HTTP 端点

**What to build:** 新增内部端点 `POST /internal/repositories/{name}/cache/invalidate`，使运维者与自动化脚本无需登录 UI 即可对单个仓库执行完整的缓存失效。proxy 仓库失效 CONTENT+METADATA 水位 token、404 负缓存与 asset 热缓存；group 仓库除自身外递归失效整棵成员树；失效经 MySQL 水位在约 2 秒内传播到所有副本。

**Blocked by:** 01 — 负缓存接入 CONTENT token 校验

**Status:** ready-for-agent

- [ ] proxy 仓库：返回 200 与 JSON 概要（仓库名、类型、失效摘要），且 CONTENT 与 METADATA token 递增、负缓存按仓库前缀清空、asset 热缓存整库清空
- [ ] group 仓库：自身 token 失效，并递归遍历成员树——成员 proxy 执行完整失效、嵌套 group 继续递归、visited 集合防环
- [ ] hosted 仓库被拒绝（非成功状态）；不存在的仓库返回 404
- [ ] 权限复用仓库 edit 判定（`RepositoryPermission` + `securityService.decide` 既有模式），无权限返回 403，不新增权限点
- [ ] 操作幂等、无事务依赖，可安全重复调用
- [ ] MockMvc 测试覆盖：无权限 403、proxy 失效后负缓存条目消失且 token 递增、group 失效递归覆盖成员树、hosted 拒绝、响应体契约

**Acceptance notes:** 本票是唯一新增的测试接缝，取最高点（HTTP），所有断言经公开 API 观察缓存状态，不 mock 失效协作对象。curl 即可演示。
