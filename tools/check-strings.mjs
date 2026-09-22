#!/usr/bin/env node
/**
 * check-strings.mjs — 资源 i18n 断言
 *
 * 用法: node tools/check-strings.mjs [res目录] [kotlin目录]
 *   例: node tools/check-strings.mjs app/src/main/res app/src/main/kotlin
 *
 * 断言（任一失败退出码 1）:
 *  1) 手写 strings.xml 双语键集一致（白名单见 NAME_WHITELIST，如产品名 app_name 单语言合法）
 *  2) 各 strings 文件内无重复键（重复资源编译失败）
 *  3) 手写与生成文件（strings_generated.xml）无撞键
 *  4) Kotlin 字符串字面量不含 CJK（注释剔除后扫描；白名单文件见 CJK_FILE_WHITELIST，
 *     如 DeviceGateScreen 的语言循环按钮字形「中/EN」为刻意语言中立）
 *
 * 生成侧（locale 源 → strings_generated.xml）的双语键集断言在 gen-strings.mjs 内，
 * 本脚本只查 res 仓库态与 Kotlin 源码态。
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const [resDir = 'app/src/main/res', kotlinDir = 'app/src/main/kotlin'] = process.argv.slice(2);

/** 单语言合法键（值即产品名等无翻译需求） */
const NAME_WHITELIST = new Set(['app_name']);

/** Kotlin CJK 字面量白名单（文件名后缀匹配）：语言循环按钮的当前态字形 */
const CJK_FILE_WHITELIST = ['DeviceGateScreen.kt'];

function extractKeys(filePath) {
  const src = readFileSync(filePath, 'utf8');
  const keys = [];
  for (const m of src.matchAll(/<string name="([^"]+)"/g)) {
    keys.push(m[1]);
  }
  return keys;
}

function duplicates(keys) {
  return keys.filter((k, i) => keys.indexOf(k) !== i);
}

let failed = false;
const fail = (msg) => {
  failed = true;
  console.error(`✗ ${msg}`);
};

// ── 1/2/3: res 目录双语与撞键 ──
const zhHand = extractKeys(join(resDir, 'values', 'strings.xml'));
const enHand = extractKeys(join(resDir, 'values-en', 'strings.xml'));
const zhGen = new Set(extractKeys(join(resDir, 'values', 'strings_generated.xml')));
const enGen = new Set(extractKeys(join(resDir, 'values-en', 'strings_generated.xml')));

const dupZh = duplicates(zhHand);
const dupEn = duplicates(enHand);
if (dupZh.length) fail(`values/strings.xml 重复键: ${dupZh.join(', ')}`);
if (dupEn.length) fail(`values-en/strings.xml 重复键: ${dupEn.join(', ')}`);

const zhSet = new Set(zhHand);
const enSet = new Set(enHand);
const onlyZh = zhHand.filter((k) => !enSet.has(k) && !NAME_WHITELIST.has(k));
const onlyEn = enHand.filter((k) => !zhSet.has(k));
if (onlyZh.length) fail(`仅中文有键（英文缺失）: ${onlyZh.join(', ')}`);
if (onlyEn.length) fail(`仅英文有键（中文缺失）: ${onlyEn.join(', ')}`);

const clashZh = zhHand.filter((k) => zhGen.has(k));
const clashEn = enHand.filter((k) => enGen.has(k));
if (clashZh.length) fail(`手写与生成文件撞键（values）: ${clashZh.join(', ')}`);
if (clashEn.length) fail(`手写与生成文件撞键（values-en）: ${clashEn.join(', ')}`);

// ── 4: Kotlin 字符串字面量 CJK 扫描（剔除注释后） ──
function listKotlinFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...listKotlinFiles(p));
    else if (name.endsWith('.kt')) out.push(p);
  }
  return out;
}

const CJK = /[\u{4e00}-\u{9fff}\u{3400}-\u{4dbf}]/u;
for (const file of listKotlinFiles(kotlinDir)) {
  if (CJK_FILE_WHITELIST.some((w) => file.endsWith(w))) continue;
  const src = readFileSync(file, 'utf8');
  // 逐字符状态机：剔除 // 行注释与 /* */ 块注释后，收集 "..." 字面量内容
  let inString = false;
  let inLineComment = false;
  let inBlockComment = false;
  let literal = '';
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    const next = src[i + 1];
    if (inLineComment) {
      if (ch === '\n') inLineComment = false;
      continue;
    }
    if (inBlockComment) {
      if (ch === '*' && next === '/') {
        inBlockComment = false;
        i++;
      }
      continue;
    }
    if (inString) {
      if (ch === '\\') {
        i++;
        continue;
      }
      if (ch === '"') {
        inString = false;
        if (CJK.test(literal)) {
          const line = src.slice(0, i).split('\n').length;
          fail(`${file}:${line} 字符串字面量含 CJK: "${literal.trim().slice(0, 40)}"`);
        }
        literal = '';
        continue;
      }
      literal += ch;
      continue;
    }
    if (ch === '/' && next === '/') {
      inLineComment = true;
      i++;
      continue;
    }
    if (ch === '/' && next === '*') {
      inBlockComment = true;
      i++;
      continue;
    }
    if (ch === '"') {
      inString = true;
    }
  }
}

console.log(
  failed
    ? 'check-strings: FAILED'
    : `check-strings: OK（手写双语 ${zhHand.length}/${enHand.length} 键，白名单 ${NAME_WHITELIST.size}，生成侧由 gen-strings.mjs 断言）`,
);
process.exit(failed ? 1 : 0);
