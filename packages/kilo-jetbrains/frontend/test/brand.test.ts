import test from "node:test";
import assert from "node:assert/strict";
import { BRAND, applyBranding } from "../src/brand.ts";

test("品牌名称替换为 Costrict", () => {
  assert.equal(BRAND.name, "Costrict");
  assert.equal(BRAND.displayName, "Costrict");
  assert.equal(BRAND.id, "costrict");
});

test("品牌图标资源指向 costrict 前缀，无 Kilo 残留", () => {
  assert.equal(BRAND.icon, "costrict.icon.svg");
  assert.equal(BRAND.statusIcon.connected, "costrict.status.connected.svg");
  assert.equal(BRAND.statusIcon.disconnected, "costrict.status.disconnected.svg");
  assert.equal(BRAND.statusIcon.reconnecting, "costrict.status.reconnecting.svg");
  assert.equal(BRAND.statusIcon.unavailable, "costrict.status.unavailable.svg");
});

test("applyBranding 替换 Kilo Code 品牌残留", () => {
  assert.equal(applyBranding("Kilo Code 已就绪"), "Costrict 已就绪");
  assert.equal(applyBranding("由 Kilo Code 提供支持"), "由 Costrict 提供支持");
  assert.equal(applyBranding("启动 Kilo"), "启动 Costrict");
});

test("applyBranding 不误伤无关词汇", () => {
  assert.equal(applyBranding("Kilometer 是长度单位"), "Kilometer 是长度单位");
  assert.equal(applyBranding("无品牌词的句子"), "无品牌词的句子");
});
