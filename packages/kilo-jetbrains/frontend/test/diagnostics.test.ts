import test from "node:test";
import assert from "node:assert/strict";
import { DIAGNOSTIC_KINDS, diagnoseFailure } from "../src/connection/diagnostics.ts";

test("诊断种类恰为三种", () => {
  assert.deepEqual([...DIAGNOSTIC_KINDS], [
    "daemon-not-running",
    "credentials-missing-or-invalid",
    "agent-not-ready",
  ]);
});

test("连接被拒绝：诊断为 daemon 未运行", () => {
  const d = diagnoseFailure({ code: "ECONNREFUSED" });
  assert.equal(d.kind, "daemon-not-running");
  assert.equal(d.title, "daemon 未运行");
  assert.ok(d.message.length > 0);
  assert.ok(d.hint.length > 0);
});

test("守护进程 socket 不存在：诊断为 daemon 未运行", () => {
  const d = diagnoseFailure({ code: "ENOENT", message: "connect unix socket failed" });
  assert.equal(d.kind, "daemon-not-running");
});

test("HTTP 401：诊断为凭证缺失或失效", () => {
  const d = diagnoseFailure({ httpStatus: 401 });
  assert.equal(d.kind, "credentials-missing-or-invalid");
  assert.equal(d.title, "凭证缺失或失效");
});

test("token 失效消息：诊断为凭证缺失或失效", () => {
  const d = diagnoseFailure({ message: "request failed: invalid credentials, token expired" });
  assert.equal(d.kind, "credentials-missing-or-invalid");
});

test("HTTP 503：诊断为 csc agent 未就绪", () => {
  const d = diagnoseFailure({ httpStatus: 503 });
  assert.equal(d.kind, "agent-not-ready");
  assert.equal(d.title, "csc agent 未就绪");
});

test("AGENT_NOT_READY 错误码：诊断为 csc agent 未就绪", () => {
  const d = diagnoseFailure({ code: "AGENT_NOT_READY" });
  assert.equal(d.kind, "agent-not-ready");
});
