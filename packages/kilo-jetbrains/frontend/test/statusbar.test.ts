import test from "node:test";
import assert from "node:assert/strict";
import type { ConnectionStatus } from "../src/connection/status.ts";
import { CONNECTION_STATUSES } from "../src/connection/status.ts";
import { ConnectionStatusBar } from "../src/ui/ConnectionStatusBar.ts";

const STATUS_TEXT: Record<ConnectionStatus, string> = {
  connected: "已连接",
  disconnected: "已断开",
  reconnecting: "重连中…",
  unavailable: "不可用",
};

function createBar() {
  let view: ReturnType<ConnectionStatusBar["currentView"]> | undefined;
  const bar = new ConnectionStatusBar((v) => {
    view = v;
  });
  return { bar, getView: () => view! };
}

test("四种状态渲染不同的图标与中文文案", () => {
  const icons = new Set<string>();
  for (const status of CONNECTION_STATUSES) {
    const { bar, getView } = createBar();
    bar.setStatus(status);
    const view = getView();
    assert.equal(view.text, STATUS_TEXT[status]);
    assert.ok(view.icon.startsWith("costrict.status."));
    icons.add(view.icon);
  }
  assert.equal(icons.size, 4);
});

test("状态栏 tooltip 包含 Costrict 品牌", () => {
  const { bar, getView } = createBar();
  bar.setStatus("connected");
  assert.ok(getView().tooltip.includes("Costrict"));
});

test("连接失败时 tooltip 显示诊断信息", () => {
  const { bar, getView } = createBar();
  bar.setStatus("unavailable");
  bar.reportFailure({ code: "ECONNREFUSED" });
  const tooltip = getView().tooltip;
  assert.ok(tooltip.includes("daemon 未运行"), `tooltip 应包含诊断标题: ${tooltip}`);
});

test("不同失败原因显示对应诊断", () => {
  const cases = [
    [{ code: "ECONNREFUSED" }, "daemon 未运行"],
    [{ httpStatus: 401 }, "凭证缺失或失效"],
    [{ httpStatus: 503 }, "csc agent 未就绪"],
  ] as const;
  for (const [signal, expectedTitle] of cases) {
    const { bar, getView } = createBar();
    bar.setStatus("unavailable");
    bar.reportFailure(signal);
    assert.ok(getView().tooltip.includes(expectedTitle));
  }
});

test("状态变化时刷新 UI", () => {
  const { bar, getView } = createBar();
  bar.setStatus("reconnecting");
  assert.equal(getView().text, "重连中…");
  bar.setStatus("connected");
  assert.equal(getView().text, "已连接");
});

test("恢复连接后清除诊断信息", () => {
  const { bar, getView } = createBar();
  bar.setStatus("unavailable");
  bar.reportFailure({ code: "ECONNREFUSED" });
  bar.setStatus("connected");
  assert.ok(!getView().tooltip.includes("daemon 未运行"));
});

test("currentView 可随时查询当前视图状态", () => {
  const { bar } = createBar();
  bar.setStatus("reconnecting");
  assert.equal(bar.currentView().text, "重连中…");
});
