// 把 SVG path 规范化为「命令 + 空格分隔的参数」形式，消除 `a.748.748,0,0,0`
// 这类省略分隔符写法带来的歧义，便于可靠地按 token 处理。
//
// 关键：必须按 SVG 数字文法切分，`748.748` 是两个数（点后不接数字时点是分隔符）。
//
// 用法: node normalize-path.js "<pathData>"
'use strict';

const CMD_RE = /^[MmLlHhVvCcSsQqTtAaZz]$/;

/** 扫描出所有 token：命令字母或数字。 */
function tokenize(d) {
  const tokens = [];
  let i = 0;
  const n = d.length;
  while (i < n) {
    const c = d[i];
    if (CMD_RE.test(c)) { tokens.push({ type: 'cmd', value: c }); i++; continue; }
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',') { i++; continue; }
    // 读一个数字
    const start = i;
    if (d[i] === '-' || d[i] === '+') i++;
    let sawDot = false;
    while (i < n) {
      const ch = d[i];
      if (ch >= '0' && ch <= '9') { i++; continue; }
      // 点只有在后面紧跟数字时才是小数点；否则它是下一个数字的分隔符
      if (ch === '.' && !sawDot) {
        if (i + 1 < n && d[i + 1] >= '0' && d[i + 1] <= '9') { sawDot = true; i++; continue; }
        break;
      }
      break;
    }
    if (i === start) { i++; continue; } // 容错：跳过无法识别的字符
    tokens.push({ type: 'num', value: d.slice(start, i) });
  }
  return tokens;
}

const d = process.argv[2];
const tokens = tokenize(d);
// 输出：命令字母**独占一个 token**，与参数之间用空格分隔。
// 注意必须让命令独立成词（`M 1 2` 而不是 `M1 2`），
// 否则下游按空白切分后拿不到纯命令 token，会整条路径解析失败。
let s = '';
for (const t of tokens) {
  if (s.length) s += ' ';
  s += t.value;
}
console.log(s);
const nums = tokens.filter((t) => t.type === 'num').length;
const cmds = tokens.filter((t) => t.type === 'cmd').map((t) => t.value).join('');
console.error(`数字 ${nums} 个，命令序列 ${cmds}`);
