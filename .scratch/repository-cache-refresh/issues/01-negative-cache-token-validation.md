# 01 — 负缓存接入 CONTENT token 校验

**What to build:** 让 404 负缓存的失效在多副本部署中即时生效：当仓库的 CONTENT 水位 token 被 bump（写路径或缓存失效操作）后，所有副本在 token 传播延迟（约 2 秒）内不再命中该仓库的负缓存条目。未注入 token 控制器的使用场景保持现有纯 TTL 行为不变。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 负缓存条目写入时嵌入写入时刻的 CONTENT token；读取命中前校验 token，失配即丢弃条目并视为未命中
- [ ] token 校验读取走本地 token 缓存（默认 2 秒 TTL），TTL 保留为负缓存条目的安全网
- [ ] 未注入 token 控制器时，行为退化为现有纯 TTL 语义（写入 404 标记、TTL 内命中），既有构造器保持可用，既有使用方无需修改即可编译通过
- [ ] 单元测试覆盖：token 失配即未命中并清除条目、token 匹配且未超 TTL 时命中、无控制器时退化行为
- [ ] 类文档写明多副本语义：token 校验、传播延迟、TTL 安全网

**Acceptance notes:** 本票交付的是后端行为切片（无 API/UI 层），可验证行为是「写路径 bump CONTENT token 后负缓存跨副本失效」。使用 `GroupMemberAssetCache` 的 token 校验模式作为既有先例。
