Status: ready-for-agent

# 仓库行级缓存刷新（invalidate）功能 Spec

## Problem Statement

kkrepo 的 proxy / group 仓库在多副本部署下依赖多层缓存（proxy 元数据新鲜度水位 token、404 负缓存、asset 下载路径热缓存、group 成员聚合缓存）。当上游制品源发布了新内容，管理员没有任何操作入口主动清除这些缓存，只能等待 TTL 自然过期（负缓存最长 5 分钟）。多副本部署进一步放大了问题：即使清掉一个副本的本地缓存，其他副本仍可能继续返回陈旧的 404 或元数据。运维侧需要一个即时的、跨副本生效的缓存失效操作入口。

## Solution

在 admin-ui 的仓库列表中，为 proxy 与 group 类型的每一行增加一个「refresh cache」行级按钮。点击后调用新的内部 HTTP 端点，对目标仓库执行缓存失效（invalidate）：proxy 仓库失效其 CONTENT / METADATA 水位 token、404 负缓存与 asset 热缓存；group 仓库除自身外，递归失效整棵成员树的缓存。失效经 MySQL 水位在约 2 秒内传播到所有副本；负缓存接入 token 校验后同样具备跨副本失效能力。操作要求仓库 edit 权限，成功后 toast 反馈。

## User Stories

1. 作为仓库管理员，我希望 admin-ui 仓库列表中每个 proxy 行都有刷新缓存按钮，以便在不重启服务的情况下强制仓库重新拉取上游内容。
2. 作为仓库管理员，我希望 group 行也有同样的按钮，以便一次点击刷新整条聚合链。
3. 作为仓库管理员，我希望 hosted 行不显示该按钮，以便界面不提供对其无意义的操作。
4. 作为仓库管理员，我希望该操作与编辑仓库共用同一权限判定，以便只有被授权的操作者能触发缓存失效。
5. 作为没有 edit 权限的用户，我希望失效请求被拒绝（403），以便无法触发无权执行的缓存失效。
6. 作为多副本部署的运维者，我希望失效传播到所有副本，以便无论下一个请求落到哪个 pod 都能观察到新的上游数据。
7. 作为运维者，我希望陈旧的 404 负缓存条目在每个副本上都被丢弃，以便刚发布的上游制品能从任意 pod 立即获取。
8. 作为 proxy 仓库的客户端，我希望刷新后的下一次请求重新查询上游，以便无需等待 TTL 到期就能取到新发布的制品或元数据。
9. 作为命中未接收点击的副本的客户端，我希望刷新在 token 传播延迟（约 2 秒）内生效，以便不会看到持续数分钟的陈旧结果。
10. 作为刷新 group 的管理员，我希望成员树中的所有 proxy 成员也被失效，以便无论嵌套多深刷新都是完整的。
11. 作为刷新 group 的管理员，我希望嵌套 group 成员被递归处理，以便 group-of-groups 场景正常工作。
12. 作为管理员，我希望该操作幂等且可安全重复，以便误双击不会造成任何损害。
13. 作为管理员，我希望操作有即时反馈（成功/失败 toast），以便确认刷新是否生效。
14. 作为管理员，我希望没有确认对话框，因为失效是可逆的低风险操作。
15. 作为管理员，我希望端点响应描述失效内容，以便日志与自动化能确认操作结果。
16. 作为管理员，我希望按钮遵循既有行级操作约定（小按钮、请求期间禁用），以便 UI 保持一致。
17. 作为 kkrepo 开发者，我希望负缓存在未注入 token 控制器时保持原有纯 TTL 行为，以便既有嵌入/测试用法不受影响。
18. 作为 kkrepo 开发者，我希望 asset 热缓存继续沿用其既有水位机制，以便不引入新的协调机制。
19. 作为 browse-ui 用户，我希望它保持不变，以便普通浏览用户无法触发运维性失效操作。
20. 作为运维者，我希望有自动化测试覆盖权限拒绝、proxy 失效、group 成员树失效与 hosted 拒绝，以便端点契约不受回归破坏。

## Implementation Decisions

- 界面范围：仅 admin-ui 仓库列表，browse-ui 不改动。
- 按钮显示条件：行仓库类型为 proxy 或 group；hosted 不渲染。
- 操作语义：失效（invalidate）而非主动重建——缓存条目被丢弃，下一次请求时重新向上游或数据库拉取。
- 端点契约：`POST /internal/repositories/{name}/cache/invalidate`。成功返回 200 与 JSON 概要（仓库名、类型、失效内容摘要）；hosted 类型拒绝；无权限返回 403。
- 权限：复用 `RepositoryPermission(仓库名, format, "", EDIT)` + `securityService.decide` 的既有判定模式（与编辑按钮同权），不新增权限点。
- proxy 失效内容：bump CONTENT 与 METADATA 水位 token（MySQL `cache_version` 表）、按仓库前缀清空 404 负缓存、整体清空 asset 元数据热缓存（其自带独立水位保证跨副本传播）。
- group 失效内容：对 group 自身执行 token 失效，并递归遍历成员树（visited 集合防环）：成员 proxy 执行完整三件套，成员 group 继续递归。
- 跨副本语义：负缓存条目写入时嵌入 CONTENT token，读取时经 `isStale` 校验（`GroupMemberAssetCache` 的既有模式），token 失配即丢弃；TTL 保留为安全网。asset 热缓存沿用其独立水位的跨副本机制。token 传播延迟受本地 token TTL（默认 2 秒）约束。
- 兼容性：`ProxyNegativeCache` 在未注入 token 控制器时退化为现有纯 TTL 行为；既有构造器保持可用。
- 前端交互：行内第三个小按钮（文案 `refresh cache`，硬编码英文，与 edit/delete 一致）；点击后按钮禁用直至请求结束；成功/失败 toast；不重载列表（缓存失效不影响列表数据本身）。
- 前端路由：复用仓库表格既有的行级事件委托模式，按按钮 class 分发。
- 失效逻辑放置于 controller（与既有 docker 缓存清理端点先例一致），不新增 service 层抽象。
- 不做：compat-test（`/internal/*` 为 kkrepo 自有运维 API，非 Nexus 兼容面，与 docker 缓存清理先例一致）、i18n 字典扩展、前端自动化测试、权限模型扩展、schema 变更。

## Testing Decisions

- 好测试的标准：通过 HTTP 接缝观察外部行为（状态码、响应体、调用后缓存的可观察状态），不断言内部实现细节；失效协作对象使用真实实例，不 mock。
- 主接缝（唯一新增接缝，取最高点）：`POST /internal/repositories/{name}/cache/invalidate`，用 server 模块 MockMvc 测试覆盖：无权限 403、proxy 失效后负缓存条目消失且水位 token 递增、group 失效递归覆盖成员树、hosted 拒绝、响应体契约。
- 次接缝（既有类级接缝）：`ProxyNegativeCache` 单元测试覆盖 token 失配即未命中、未注入控制器时退化为纯 TTL。
- 测试先例：既有 controller MockMvc 测试（仓库列表与权限过滤测试）与既有 `ProxyNegativeCache` 单元测试用法（如 Cargo 代理服务测试）。
- 运行方式：`mvn -pl server -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false test`（项目约定）。

## Out of Scope

- browse-ui 的任何改动
- hosted 仓库的缓存操作
- 主动重建/预取上游内容（refresh = 失效而非重建）
- Nexus 兼容性黑盒测试（compat-test）
- 认证缓存、限流计数等与单仓库无关的缓存
- 新增缓存专属权限点
- admin-ui 前端测试基建
- i18n 字典改造

## Further Notes

- 多副本语义文档：相关类 javadoc 需写明 token 校验与传播延迟语义，满足项目「本地热缓存多副本语义必须说明」的强制规则。
- token 传播延迟约 2 秒（本地 token TTL 默认值），期间其他副本可能短暂提供陈旧数据，属于可接受的收敛窗口。
- 写路径 bump CONTENT token 会连带全量丢弃该仓库的负缓存条目（保守正确；写频繁的 proxy 仓库罕见，命中率成本可忽略）。
- 本仓库无 CONTEXT.md 与 ADR；本次决议未产生新的领域词汇或不可逆决策，按规则不创建。
