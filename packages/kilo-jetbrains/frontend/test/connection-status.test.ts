import test from "node:test";
import assert from "node:assert/strict";
import { CONNECTION_STATUSES, ConnectionStatusStore } from "../src/connection/status.ts";

test("支持四种连接状态", () => {
  assert.deepEqual([...CONNECTION_STATUSES], [
    "connected",
    "disconnected",
    "reconnecting",
    "unavailable",
  ]);
});

test("初始状态为 disconnected", () => {
  const store = new ConnectionStatusStore();
  assert.equal(store.get(), "disconnected");
});

test("set 更新状态并通知订阅者", () => {
  const store = new ConnectionStatusStore();
  const seen: string[] = [];
  store.subscribe((s) => seen.push(s));
  store.set("reconnecting");
  store.set("connected");
  assert.equal(store.get(), "connected");
  assert.deepEqual(seen, ["reconnecting", "connected"]);
});

test("相同状态不重复通知", () => {
  const store = new ConnectionStatusStore();
  let calls = 0;
  store.subscribe(() => calls++);
  store.set("connected");
  store.set("connected");
  assert.equal(calls, 1);
});

test("unsubscribe 后不再接收通知", () => {
  const store = new ConnectionStatusStore();
  let calls = 0;
  const off = store.subscribe(() => calls++);
  off();
  store.set("connected");
  assert.equal(calls, 0);
});

test("非法状态值抛出错误", () => {
  const store = new ConnectionStatusStore();
  assert.throws(() => store.set("online" as never));
});
