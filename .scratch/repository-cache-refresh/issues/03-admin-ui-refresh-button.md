# 03 — admin-ui 行级刷新按钮

**What to build:** 管理员在 admin-ui 仓库列表的每个 proxy 与 group 行上看到一个「refresh cache」行级按钮，点击即对该仓库（含 group 成员树）执行缓存失效并获得即时反馈；hosted 行不显示该按钮。

**Blocked by:** 02 — 缓存失效 HTTP 端点

**Status:** ready-for-agent

- [ ] proxy 与 group 类型的行渲染第三个行级小按钮，文案 `refresh cache`（硬编码英文，与 edit/delete 风格一致），hosted 行不渲染
- [ ] 点击按钮调用失效端点（仓库名 URL 编码）；请求期间按钮禁用，结束后恢复
- [ ] 成功显示成功 toast（含仓库名），失败（含 403）显示错误 toast
- [ ] 列表不因该操作重载（缓存失效不影响列表数据本身）
- [ ] 事件处理复用仓库表格既有行级事件委托模式，不新增全局监听
- [ ] 浏览器手动验证：对 proxy 行点击后负缓存与 token 失效可观察（或经端点测试佐证），按钮状态与 toast 符合预期

**Acceptance notes:** 本票为纯前端切片，依赖 #02 已定死的端点契约；无前端测试基建，验收以既有测试佐证 + 手动验证为准。
