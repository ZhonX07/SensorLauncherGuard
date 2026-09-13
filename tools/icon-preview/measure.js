// 客观测量图标质量，避免只靠肉眼主观判断。
// 输出：墨迹占比、边缘密度（细节碎不碎）、左右对称性、24/48px 下的可辨识度代理指标。
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');
const { execFileSync } = require('child_process');

function readPng(buf) {
  let pos = 8, w = 0, h = 0; const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); }
    if (type === 'IDAT') idat.push(data);
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const px = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    const off = y * (w * 4 + 1) + 1;
    raw.copy(px, y * w * 4, off, off + w * 4);
  }
  return { w, h, px };
}

// 以背景色为基准，统计每个像素相对背景的"着墨强度" 0..1
function inkMask(img, bg) {
  const m = new Float32Array(img.w * img.h);
  for (let i = 0; i < img.w * img.h; i++) {
    const o = i * 4;
    const d = Math.abs(img.px[o] - bg[0]) + Math.abs(img.px[o + 1] - bg[1]) + Math.abs(img.px[o + 2] - bg[2]);
    m[i] = Math.min(1, d / 255);
  }
  return m;
}

function metrics(img, bg) {
  const m = inkMask(img, bg);
  const { w, h } = img;
  // 墨迹占比（只统计图标内容区域中心 2/3，避开留白）
  let ink = 0, total = 0;
  const x0 = Math.floor(w / 6), x1 = Math.floor((w * 5) / 6);
  const y0 = Math.floor(h / 6), y1 = Math.floor((h * 5) / 6);
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { ink += m[y * w + x]; total++; }
  // 边缘密度：与右/下邻居差异超过阈值的像素比例（细节碎 = 密度高）
  let edges = 0;
  for (let y = y0; y < y1 - 1; y++) {
    for (let x = x0; x < x1 - 1; x++) {
      const d = Math.abs(m[y * w + x] - m[y * w + x + 1]) + Math.abs(m[(y + 1) * w + x] - m[y * w + x]);
      if (d > 0.5) edges++;
    }
  }
  // 左右对称性：镜像像素的平均差异（越小越对称）
  let asym = 0, n = 0;
  for (let y = y0; y < y1; y++) {
    for (let x = x0; x < x1; x++) { asym += Math.abs(m[y * w + x] - m[y * w + (w - 1 - x)]); n++; }
  }
  return {
    inkRatio: +(ink / total).toFixed(3),
    edgeDensity: +(edges / total).toFixed(3),
    asymmetry: +(asym / n).toFixed(4),
  };
}

const outDir = process.argv[2];
const variants = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const bgHex = 'E2DFFF';
const bg = [0xE2, 0xDF, 0xFF];
const raster = path.join(__dirname, 'svgraster.js');
const rows = [];
for (const v of variants) {
  const res = { name: v.name };
  for (const s of [48, 24]) {
    const tmp = path.join(outDir, `_m_${v.name}_${s}.png`);
    execFileSync(process.execPath, [raster, v.file, tmp, String(s), bgHex, 'hires']);
    res[`s${s}`] = metrics(readPng(fs.readFileSync(tmp)), bg);
    fs.unlinkSync(tmp);
  }
  rows.push(res);
}
console.log(JSON.stringify(rows, null, 1));
