import { BRAND } from "../brand.ts";

export const zhCN = {
  pluginName: BRAND.displayName,
  connection: {
    connected: "已连接",
    disconnected: "已断开",
    reconnecting: "重连中…",
    unavailable: "不可用",
  },
  tooltip: {
    connected: `${BRAND.displayName} 已连接`,
    disconnected: `${BRAND.displayName} 已断开连接`,
    reconnecting: `${BRAND.displayName} 正在重新连接…`,
    unavailable: `${BRAND.displayName} 服务不可用`,
  },
  diagnostics: {
    "daemon-not-running": {
      title: "daemon 未运行",
      message: "无法连接本地守护进程，连接被拒绝。",
      hint: "请执行 `cs-cloud daemon start` 启动守护进程后重试。",
    },
    "credentials-missing-or-invalid": {
      title: "凭证缺失或失效",
      message: "访问凭证缺失或已失效，服务端拒绝了请求。",
      hint: "请执行 `cs-cloud auth login` 重新登录，并检查凭证配置。",
    },
    "agent-not-ready": {
      title: "csc agent 未就绪",
      message: "连接已建立，但 csc agent 尚未完成初始化。",
      hint: "请稍候重试，或执行 `csc agent status` 查看 agent 状态。",
    },
  },
} as const;
