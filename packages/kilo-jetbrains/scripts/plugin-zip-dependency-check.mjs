#!/usr/bin/env node
/**
 * G5 插件 ZIP 依赖门禁 (spec U1.3, 回归钉子 R-4, ★T0)。
 *
 * The buildPlugin ZIP must not bundle libraries the IntelliJ Platform already ships.
 * Re-bundled copies put the same classes on two classpaths; in split mode different plugin
 * classloaders then bind to different copies and cross-module calls die with
 * "LinkageError: loader constraint violation" (the original cloudFavorites defect).
 *
 * Pure-JS ZIP central-directory read — no unzip/jar tooling required.
 *
 * Usage: node scripts/plugin-zip-dependency-check.mjs [path/to/plugin.zip]
 *        (without an argument the newest build/distributions/*.zip is used)
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";

const pluginRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const distributionsDir = join(pluginRoot, "build/distributions");

/** Jar name fragments that must never appear under the ZIP's lib/ (platform-shipped). */
const FORBIDDEN_IN_LIB = [
  "kotlinx-serialization", // ships with the platform; see PrepareSandboxTask exclusion
];

const zipArg = process.argv[2];
const zipPath = zipArg ?? newestZip(distributionsDir);
if (!zipPath) {
  console.error("G5 dependency gate: no plugin ZIP found.");
  console.error(`  Build one first:  cd packages/kilo-jetbrains && ./gradlew buildPlugin`);
  console.error(`  Or pass a path:   node scripts/plugin-zip-dependency-check.mjs build/distributions/<zip>`);
  process.exit(2);
}

const entries = listZipEntries(zipPath);
const offenders = entries.filter((name) => {
  const inLib = /(^|\/)lib\//.test(name) && name.endsWith(".jar");
  if (!inLib) return false;
  return FORBIDDEN_IN_LIB.some((fragment) => name.includes(fragment));
});

console.log(`G5 dependency gate: ${zipPath}`);
console.log(`  ${entries.length} entries, ${entries.filter((e) => e.endsWith(".jar") && /lib\//.test(e)).length} bundled jars`);

if (offenders.length > 0) {
  console.error("\nFAILED — platform-shipped libraries re-bundled in lib/:");
  for (const name of offenders) console.error(`  ${name}`);
  process.exit(1);
}
console.log("PASSED — no platform-shipped library is re-bundled.");

// ---------------------------------------------------------------------

function newestZip(dir) {
  try {
    return readdirSync(dir)
      .filter((f) => extname(f) === ".zip")
      .map((f) => join(dir, f))
      .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0] ?? null;
  } catch {
    return null;
  }
}

/** Minimal ZIP reader: walks the central directory and returns entry names. */
function listZipEntries(path) {
  const buf = readFileSync(path);
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) throw new Error(`${path} is not a ZIP archive (no EOCD)`);
  const entryCount = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);
  const names = [];
  const centralDirSig = Buffer.from([0x50, 0x4b, 0x01, 0x02]);
  for (let i = 0; i < entryCount; i += 1) {
    if (buf.indexOf(centralDirSig, offset) !== offset) throw new Error(`${path}: corrupt central directory at ${offset}`);
    const nameLength = buf.readUInt16LE(offset + 28);
    const extraLength = buf.readUInt16LE(offset + 30);
    const commentLength = buf.readUInt16LE(offset + 32);
    names.push(buf.toString("utf8", offset + 46, offset + 46 + nameLength));
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return names;
}
