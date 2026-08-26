import { zhCN } from "../i18n/zh-CN.ts";

export const DIAGNOSTIC_KINDS = [
  "daemon-not-running",
  "credentials-missing-or-invalid",
  "agent-not-ready",
] as const;

export type DiagnosticKind = (typeof DIAGNOSTIC_KINDS)[number];

export interface FailureSignal {
  code?: string;
  httpStatus?: number;
  message?: string;
}

export interface Diagnostic {
  kind: DiagnosticKind;
  title: string;
  message: string;
  hint: string;
}

function isCredentialsFailure(signal: FailureSignal): boolean {
  if (signal.httpStatus === 401 || signal.httpStatus === 403) return true;
  const text = `${signal.code ?? ""} ${signal.message ?? ""}`.toLowerCase();
  return /unauthorized|forbidden|invalid credentials|token (expired|invalid|missing)|credential/.test(text);
}

function isDaemonFailure(signal: FailureSignal): boolean {
  const code = signal.code?.toUpperCase();
  if (code === "ECONNREFUSED" || code === "ENOENT" || code === "ECONNRESET") return true;
  const text = `${signal.message ?? ""}`.toLowerCase();
  return /daemon.*(not running|refused|stopped|未运行)/.test(text);
}

function isAgentFailure(signal: FailureSignal): boolean {
  if (signal.httpStatus === 503) return true;
  const text = `${signal.code ?? ""} ${signal.message ?? ""}`.toLowerCase();
  return /agent.*(not ready|unavailable|initializing|未就绪)/.test(text);
}

/**
 * 将连接失败信号归类为三种诊断之一。
 * 优先级：凭证 > daemon > agent；无法识别的失败视为 agent 未就绪。
 */
export function diagnoseFailure(signal: FailureSignal): Diagnostic {
  const kind: DiagnosticKind = isCredentialsFailure(signal)
    ? "credentials-missing-or-invalid"
    : isDaemonFailure(signal)
      ? "daemon-not-running"
      : isAgentFailure(signal)
        ? "agent-not-ready"
        : "agent-not-ready";
  return { kind, ...zhCN.diagnostics[kind] };
}
