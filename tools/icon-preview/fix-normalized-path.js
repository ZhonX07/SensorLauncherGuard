// 修复规范化输出中「命令与数字粘连导致命令丢失」的情况。
//
// 背景：SVG 里 `a.82.82,0,0,0-.532.216` 表示紧接上一个圆弧的**第二个**圆弧——
// 命令字母 `a` 直接贴着数字，中间没有分隔符。朴素的 `split(/\s+/)` 会把 `a.82`
// 当作一个数字 token，命令就此丢失，后续参数全体错位（曾导致几何完全变形）。
//
// 本脚本按「每个命令应有多少参数」逐个消费数字；一旦数字还剩、而当前命令的参数已满，
// 就把多出来的数字重新解释为「隐式重复该命令」，从而恢复正确结构。
//
// 用法: node fix-normalized-path.js --file <规范化的path文件>
'use strict';

const fs = require('fs');
const CMD_RE = /^[MmLlHhVvCcSsQqTtAaZz]$/;
const NEED = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
const NUM_RE = /^[+-]?(?:\d+\.?\d*|\.\d+)$/;

const argv = process.argv.slice(2);
const src = argv[0] === '--file'
  ? fs.readFileSync(argv[1], 'utf8').trim()
  : argv[0];

// 第 1 步：按「命令 + 其全部参数」切出原子段。
// 数字可能贴在命令上（`a.82`），也可能独立；用扫描而非 split。
const atoms = [];
let i = 0;
while (i < src.length) {
  const c = src[i];
  if (c === ' ' || c === '\t' || c === '\n') { i++; continue; }
  if (CMD_RE.test(c)) {
    atoms.push({ cmd: c, nums: [] });
    i++;
    continue;
  }
  // 读一个数字
  const m = /^[+-]?(?:\d+\.?\d*|\.\d+)/.exec(src.slice(i));
  if (m) {
    if (!atoms.length) { i += m[0].length; continue; } // 前导数字，丢弃
    atoms[atoms.length - 1].nums.push(m[0]);
    i += m[0].length;
    continue;
  }
  i++;
}

// 第 2 步：把每个原子段的数字按 NEED 重新分组，多出来的部分作为隐式重复命令。
const out = [];
let fixed = 0;
for (const a of atoms) {
  const need = NEED[a.cmd.toUpperCase()] ?? 0;
  if (need === 0) { out.push({ cmd: a.cmd, nums: [] }); continue; }
  const nums = a.nums;
  if (nums.length === 0) { out.push({ cmd: a.cmd, nums }); continue; }
  if (nums.length === need) { out.push({ cmd: a.cmd, nums }); continue; }
  // 参数多于一份：按 need 个一组切开，后续组视为隐式重复该命令。
  // 圆弧同理——`a` 后面出现 14 个数字就是两个圆弧（各 7 个参数），
  // 早前这里对圆弧特判跳过，导致第二段圆弧的参数被整个丢掉（曾使几何变形）。
  for (let k = 0; k < nums.length; k += need) {
    const chunk = nums.slice(k, k + need);
    let cmd = a.cmd;
    if (k > 0 && cmd === 'M') cmd = 'L';
    if (k > 0 && cmd === 'm') cmd = 'l';
    if (k > 0) fixed++;
    out.push({ cmd, nums: chunk });
  }
}

const result = out.map((o) => [o.cmd, ...o.nums].join(' ')).join(' ');
console.log(result);

// 校验
const inNums = (src.match(/-?(?:\d+\.?\d*|\.\d+)/g) || []).length;
const outNums = out.reduce((n, o) => n + o.nums.length, 0);
console.error(`数字: 输入 ${inNums} / 输出 ${outNums} ${inNums === outNums ? '✓' : '✗'}`);
console.error(`修复的隐式重复: ${fixed} 处`);
console.error('命令序列: ' + out.map((o) => o.cmd).join(''));
