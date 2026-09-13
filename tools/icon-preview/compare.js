// 把若干候选图标各渲染成 192/96/48 三档，横向拼成一张对比图。
// 用途：在真实尺寸下判断哪个设计在小图标时仍然可辨识。
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// 复用 svgraster 的核心：直接引入其函数不方便（脚本式），这里改为子进程渲染后拼图。
const zlib = require('zlib');

function readPng(buf) {
  // 仅解析本工具自己产出的 PNG（8bit RGBA，无隔行）
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

function blit(dst, dw, dh, src, sx, sy) {
  for (let y = 0; y < src.h; y++) {
    for (let x = 0; x < src.w; x++) {
      const s = (y * src.w + x) * 4, d = ((sy + y) * dw + (sx + x)) * 4;
      if (d + 3 >= dst.length) continue;
      dst[d] = src.px[s]; dst[d + 1] = src.px[s + 1];
      dst[d + 2] = src.px[s + 2]; dst[d + 3] = 255;
    }
  }
}

function crc32(buf) {
  let c, table = [];
  for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; table[n] = c >>> 0; }
  let crc = 0xffffffff;
  for (const b of buf) crc = table[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function writePng(p, w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(p, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
  ]));
}

const outDir = process.argv[2];
const bg = process.argv[3] || 'E2DFFF';
const variants = JSON.parse(fs.readFileSync(process.argv[4], 'utf8'));
const raster = path.join(__dirname, 'svgraster.js');
const sizes = [192, 96, 48, 24];
const pad = 12;

const tiles = [];
for (const v of variants) {
  const row = [];
  for (const s of sizes) {
    const tmp = path.join(outDir, `_tmp_${v.name}_${s}.png`);
    // hires：先 8x 渲染再降采样，逼近真实设备的模糊程度
    execFileSync(process.execPath, [raster, v.file, tmp, String(s), bg, 'hires']);
    row.push(readPng(fs.readFileSync(tmp)));
    fs.unlinkSync(tmp);
  }
  tiles.push(row);
}

const cellW = sizes[0] + pad * 2;
const cellH = sizes[0] + pad * 2;
const dw = cellW * sizes.length;
const dh = cellH * variants.length;
const dst = Buffer.alloc(dw * dh * 4, 255);
for (let i = 0; i < dw * dh; i++) { dst[i * 4] = 245; dst[i * 4 + 1] = 245; dst[i * 4 + 2] = 248; dst[i * 4 + 3] = 255; }

tiles.forEach((row, r) => {
  row.forEach((img, c) => {
    const sx = c * cellW + pad + Math.floor((sizes[0] - img.w) / 2);
    const sy = r * cellH + pad + Math.floor((sizes[0] - img.h) / 2);
    blit(dst, dw, dh, img, sx, sy);
  });
});
const out = path.join(outDir, 'compare.png');
writePng(out, dw, dh, dst);
console.log(JSON.stringify({ out, dw, dh, rows: variants.map((v) => v.name), cols: sizes }));
