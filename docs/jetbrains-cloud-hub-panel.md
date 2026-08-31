# Cloud Hub（JetBrains 端用户指南）

Cloud Hub 是 Costrict 云端收藏在 JetBrains 端的管理入口。

## 前置条件
- 已安装 csc CLI 并启动 cs-cloud daemon（Kilo Code 连接面板可一键安装/启动）
- 已登录 Costrict（`csc auth login`）

## 使用
1. 打开 Settings → Tools → Kilo Code → Agent Behavior → Cloud Hub
2. 列表按 Skills / Agents / Commands / MCP 分组展示云端收藏
3. 点击条目行的 Enable 安装并启用；点击 Disable 停用
4. 状态徽标：已启用（当前生效）/ 已下载（落盘未激活）/ 云端（未安装）/ 已停用

## 生效方式
启停写入全局 `~/.costrict` 配置世界，由 cs-cloud daemon 单点管理；
条目启用后在新会话中立即可用。版本更新与孤儿清理由 daemon 后台同步自动完成（约 5 分钟节奏）。

## 排障
- 提示未登录：Kilo Code 连接面板 → 登录
- 提示 daemon 未运行：Kilo Code 连接面板 → 启动/安装
