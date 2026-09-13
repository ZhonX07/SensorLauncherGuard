// 在「规范化后的 path」（命令后跟空格分隔参数）上缩放与平移。
//
// 为什么要有这个脚本，而不是直接在原始 path 上做：
// 原始 SVG path 允许省略分隔符（`.748.748`、`0,0,0-.529-.224`），
// 靠正则切分数字极易误判——曾把两个圆弧的 14 个参数当成一个 7 参数的圆弧，
// 生成畸形几何。先经 normalize-path.js 规范化为空白分隔，再按命令逐个处理就无歧义了。
//
// 各命令参数中哪些是「坐标」需要平移：
//   M/L/T  全部是坐标
//   H/V    单个坐标
//   C      三组坐标
//   S/Q    两组坐标
//   A      rx,ry 需按比例缩放但不平移；x-axis-rotation 与两个 flag 原样保留；
//          仅最后两参数（终点）缩放并平移
//   Z      无参数
//
// 用法: node scale-normalized-path.js "<规范化path>" <scale> [dx] [dy]
'use strict';

const COORD_COUNT = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
const CMD_RE = /^[MmLlHhVvCcSsQqTtAaZz]$/;

function scaleNormalized(d, scale, dx, dy) {
  const toks = d.trim().split(/\s+/);
  const fmt = (v) => String(Math.round(v * 1000) / 1000);
  const parts = [];
  let i = 0;
  while (i < toks.length) {
    const cmd = toks[i++];
    if (!CMD_RE.test(cmd)) continue;
    const upper = cmd.toUpperCase();
    const isRelative = cmd !== upper; // 小写 = 相对命令
    // 相对命令的参数是「相对上一位置的增量」：缩放要乘，**偏移绝不能加**。
    // 给它加偏移会把整条路径撕裂（曾导致几何完全变形）。
    const ox = isRelative ? 0 : dx;
    const oy = isRelative ? 0 : dy;
    const need = COORD_COUNT[upper] ?? 0;
    const args = [];
    for (let p = 0; p < need && i < toks.length; p++) args.push(parseFloat(toks[i++]));
    const out = [];
    if (upper === 'H') {
      out.push(fmt(args[0] * scale + ox));
    } else if (upper === 'V') {
      out.push(fmt(args[0] * scale + oy));
    } else if (upper === 'A') {
      out.push(fmt(args[0] * scale));   // rx
      out.push(fmt(args[1] * scale));   // ry
      out.push(fmt(args[2]));           // x-axis-rotation（角度）
      out.push(String(args[3]));        // large-arc-flag
      out.push(String(args[4]));        // sweep-flag
      out.push(fmt(args[5] * scale + ox)); // 终点 x
      out.push(fmt(args[6] * scale + oy)); // 终点 y
    } else {
      for (let p = 0; p < args.length; p += 2) {
        out.push(fmt(args[p] * scale + ox));
        if (p + 1 < args.length) out.push(fmt(args[p + 1] * scale + oy));
      }
    }
    parts.push(cmd + ' ' + out.join(' '));
  }
  return parts.join(' ');
}

const fs = require('fs');
// 支持两种调用：直接传 path 字符串，或 --file <文件>（推荐，避免 shell 参数被拆散）
let d, scaleArg, dxArg, dyArg;
const argv = process.argv.slice(2);
if (argv[0] === '--file') {
  d = fs.readFileSync(argv[1], 'utf8').trim();
  scaleArg = argv[2]; dxArg = argv[3]; dyArg = argv[4];
} else {
  [d, scaleArg, dxArg, dyArg] = argv;
}
const result = scaleNormalized(d, parseFloat(scaleArg), parseFloat(dxArg ?? '0'), parseFloat(dyArg ?? '0'));
console.log(result);
const nIn = (d.match(/-?(?:\d+\.?\d*|\.\d+)/g) || []).length;
const nOut = (result.match(/-?(?:\d+\.?\d*|\.\d+)/g) || []).length;
console.error(`数字: 原 ${nIn} / 输出 ${nOut} ${nIn === nOut ? '✓' : '✗'}`);
