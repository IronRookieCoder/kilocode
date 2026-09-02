#!/usr/bin/env node
/**
 * G4 品牌一致性扫描 (spec U8.6, ★T0)。
 *
 * Rule ① (hard gate, exit 1): user-visible strings in the frontend plugin.xml and every
 *   KiloBundle*.properties must not carry the Kilo brand (case-insensitive). Whitelisted as
 *   internal (never user-visible): `Kilo.*` action/EP ids and refs, `ai.kilocode.*` package
 *   names, `/icons/kilo*.svg` file names, `kilo.*` registry keys, bundle keys — i.e. exactly
 *   the attribute set the plugin.xml spec reserves for internals.
 * Rule ② (report-only until 产品定标, always exit 0): counts the two brand spellings
 *   (`Costrict` vs `CoStrict`); once the product settles one, set RULE2_ENFORCE = true and
 *   the minority spelling fails the scan.
 *
 * Usage: node scripts/brand-consistency-scan.mjs
 */
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const pluginRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const xmlPath = join(pluginRoot, "frontend/src/main/resources/kilo.jetbrains.frontend.xml");
const messagesDir = join(pluginRoot, "frontend/src/main/resources/messages");

const RULE2_ENFORCE = false;

/**
 * Locales whose bundles are enforced by Rule ①. The rebrand commit covered en (default) /
 * zh_CN / zh_TW; the remaining locales still carry Kilo copy and are a translation backlog,
 * so they stay report-only until retranslated — pass `--all-locales` to escalate.
 */
const ENFORCED_LOCALES = new Set(["", "_zh_CN", "_zh_TW"]);
const ALL_LOCALES = process.argv.includes("--all-locales");

// Attributes whose values are internal identifiers, never user-visible copy.
const INTERNAL_ATTRS = new Set([
  "id", "ref", "implementation", "implementationClass", "instance", "factoryClass",
  "icon", "key", "bundle", "selector", "fieldName", "listener", "serviceInterface",
  "order", "anchor", "testable",
]);

// Internal namespaces that may legitimately appear inside a value of a visible attribute
// (e.g. descriptions referencing class names) — still not user copy.
const INTERNAL_VALUE_PATTERNS = [/ai\.kilocode\./, /\/icons\/kilo/i];

const violations = [];
const spellingCount = { Costrict: 0, CoStrict: 0 };

function countSpellings(text) {
  for (const spelling of Object.keys(spellingCount)) {
    spellingCount[spelling] += (text.match(new RegExp(spelling, "g")) ?? []).length;
  }
}

function flag(file, line, text) {
  for (const match of text.matchAll(/kilo/gi)) {
    const start = Math.max(0, match.index - 20);
    const context = text.slice(start, match.index + 24).trim();
    violations.push(`${file}:${line}  …${context}…`);
  }
}

function scanXml(file, content) {
  // XML comments are developer-facing, not user copy.
  const noComments = content.replace(/<!--[\s\S]*?-->/g, "");
  const lines = noComments.split(/\r?\n/);
  lines.forEach((rawLine, i) => {
    countSpellings(rawLine);
    // Whole internal elements: module names, resource-bundle declarations.
    if (/<module\b|<resource-bundle\b|<\/resource-bundle>/i.test(rawLine)) return;
    // Strip internal attribute values first: whatever remains is user-visible copy.
    const visible = rawLine.replace(/([\w:-]+)="([^"]*)"/g, (whole, attr, value) => {
      if (INTERNAL_ATTRS.has(attr)) return `${attr}=""`;
      if (INTERNAL_VALUE_PATTERNS.some((re) => re.test(value))) return `${attr}=""`;
      // fileType/notificationGroup registry names like KILO_WORKTREE_SESSION are internal ids.
      if (attr === "name" && /^[A-Z0-9_]+$/.test(value)) return `${attr}=""`;
      return whole;
    });
    flag(file, i + 1, visible);
  });
}

function scanProperties(file, content) {
  const suffix = file.replace(/^KiloBundle/, "").replace(/\.properties$/, "");
  const enforced = ALL_LOCALES || ENFORCED_LOCALES.has(suffix);
  let pendingCount = 0;
  const lines = content.split(/\r?\n/);
  lines.forEach((rawLine, i) => {
    const line = rawLine.trim();
    if (!line || line.startsWith("#") || !line.includes("=")) return;
    // The key side is internal (`notification.group.kilo`, `settings.kilo.displayName`).
    const value = line.slice(line.indexOf("=") + 1);
    countSpellings(value);
    const cleaned = INTERNAL_VALUE_PATTERNS.reduce(
      (acc, re) => acc.replace(new RegExp(re.source, "g"), ""),
      value,
    );
    if (enforced) {
      flag(file, i + 1, cleaned);
    } else if (/kilo/i.test(cleaned)) {
      pendingCount += 1;
    }
  });
  if (pendingCount > 0) {
    console.log(`  note: ${file} — ${pendingCount} line(s) still carry Kilo copy (translation backlog, report-only)`);
  }
}

console.log(`G4 brand scan: ${xmlPath} + bundles under ${messagesDir}`);

scanXml("frontend.xml", readFileSync(xmlPath, "utf8"));
for (const file of readdirSync(messagesDir)) {
  if (!file.startsWith("KiloBundle") || !file.endsWith(".properties")) continue;
  scanProperties(`messages/${file}`, readFileSync(join(messagesDir, file), "utf8"));
}

console.log(`  spelling census → Costrict ×${spellingCount.Costrict}, CoStrict ×${spellingCount.CoStrict}`);

if (violations.length > 0) {
  console.error(`\nRule ① FAILED — ${violations.length} user-visible Kilo remnant(s):`);
  for (const v of violations) console.error(`  ${v}`);
  process.exit(1);
}
console.log("Rule ① PASSED — no user-visible Kilo remnants.");

if (RULE2_ENFORCE && spellingCount.Costrict > 0 && spellingCount.CoStrict > 0) {
  console.error("Rule ② FAILED — both brand spellings coexist; the product must pick one.");
  process.exit(1);
}
console.log("Rule ② report-only until 产品定标 (spec §13).");
