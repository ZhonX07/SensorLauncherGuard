// 校验 path 中圆弧命令的 flag 合法性（第 4、5 个参数必须是 0 或 1）
const fs = require('fs');
const NEED = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
const xml = fs.readFileSync(process.argv[2], 'utf8');
const d = /pathData="([^"]+)"/.exec(xml)[1];
const t = d.trim().split(/\s+/);
let i = 0, ok = 0, bad = 0, nums = 0;
const cmds = [];
while (i < t.length) {
  const c = t[i++];
  if (!/^[MmLlHhVvCcSsQqTtAaZz]$/.test(c)) continue;
  cmds.push(c);
  const need = NEED[c.toUpperCase()] ?? 0;
  const a = [];
  for (let p = 0; p < need && i < t.length; p++) { a.push(t[i++]); nums++; }
  if (c.toUpperCase() === 'A') {
    if (/^[01]$/.test(a[3]) && /^[01]$/.test(a[4])) ok++;
    else { bad++; console.log('  非法圆弧参数:', c, a.join(' ')); }
  }
}
console.log(`  数字 ${nums} 个，命令 ${cmds.length} 个: ${cmds.join('')}`);
console.log(`  圆弧 ${ok + bad} 个，flag 合法 ${ok}，非法 ${bad}`);
process.exit(bad === 0 ? 0 : 1);
