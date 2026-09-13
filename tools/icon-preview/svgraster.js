// 简易 SVG 路径光栅化器：把 Android vector drawable 的 pathData 渲染成 PNG，
// 便于在没有设计工具的环境下预览图标效果。仅支持本仓库用到的命令与 nonzero 填充规则。
const fs = require('fs');
const zlib = require('zlib');

// ---- PNG 输出 ----
function crc32(buf) {
  let c, table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
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
function writePng(path, w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  fs.writeFileSync(path, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]));
}

// ---- 路径解析：转成多边形（贝塞尔/圆弧采样为折线）----
function parsePath(d) {
  const toks = d.match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:e[-+]?\d+)?/gi) || [];
  const polys = [];
  let cur = [], cx = 0, cy = 0, sx = 0, sy = 0, qx = 0, qy = 0, i = 0;
  const num = () => parseFloat(toks[i++]);
  const push = (x, y) => { cur.push([x, y]); cx = x; cy = y; };
  // SVG 允许省略重复的命令字母：`l1,2 3,4` 等价于 `l1,2 l3,4`。
  // 不支持这一点会静默丢掉后续坐标段，导致图形缺块（曾因此误判多个设计稿）。
  let lastCmd = '';
  // 死循环守卫：未实现的命令若只消费 0 个 token 就会空转，必须显式报错。
  // 这里踩过坑——缺了 Q 分支导致解析器静默挂死，产生的"预览图"其实是残缺的。
  let guard = 0;
  while (i < toks.length) {
    if (++guard > 1e6) throw new Error(`path parse runaway near token ${i}: ${toks.slice(Math.max(0,i-4), i+4).join(' ')}`);
    let cmd = toks[i];
    if (/^[MmLlHhVvCcSsQqTtAaZz]$/.test(cmd)) { i++; lastCmd = cmd; }
    else if (lastCmd) {
      cmd = lastCmd;
      // M 的隐式重复按 L 处理，m 按 l 处理
      if (cmd === 'M') cmd = 'L';
      else if (cmd === 'm') cmd = 'l';
    } else {
      i++; continue;
    }
    const consumedBefore = i;
    switch (cmd) {
      // 二次贝塞尔
      case 'Q': {
        const x1 = num(), y1 = num(), x = num(), y = num();
        for (let t = 1; t <= 16; t++) {
          const u = t / 16, m = 1 - u;
          cur.push([m*m*cx + 2*m*u*x1 + u*u*x, m*m*cy + 2*m*u*y1 + u*u*y]);
        }
        qx = x1; qy = y1; cx = x; cy = y; break;
      }
      case 'q': {
        const x1 = cx + num(), y1 = cy + num(), x = cx + num(), y = cy + num();
        for (let t = 1; t <= 16; t++) {
          const u = t / 16, m = 1 - u;
          cur.push([m*m*cx + 2*m*u*x1 + u*u*x, m*m*cy + 2*m*u*y1 + u*u*y]);
        }
        qx = x1; qy = y1; cx = x; cy = y; break;
      }
      // 平滑二次贝塞尔：控制点由上一控制点关于当前点反射得到
      case 'T': case 't': {
        const x = cmd === 'T' ? num() : cx + num();
        const y = cmd === 'T' ? num() : cy + num();
        const x1 = 2 * cx - qx, y1 = 2 * cy - qy;
        for (let k = 1; k <= 16; k++) {
          const u = k / 16, m = 1 - u;
          cur.push([m*m*cx + 2*m*u*x1 + u*u*x, m*m*cy + 2*m*u*y1 + u*u*y]);
        }
        qx = x1; qy = y1; cx = x; cy = y; break;
      }
      case 'M': push(num(), num()); sx = cx; sy = cy; break;
      case 'm': push(cx + num(), cy + num()); sx = cx; sy = cy; break;
      case 'L': push(num(), num()); break;
      case 'l': push(cx + num(), cy + num()); break;
      case 'H': push(num(), cy); break;
      case 'h': push(cx + num(), cy); break;
      case 'V': push(cx, num()); break;
      case 'v': push(cx, cy + num()); break;
      case 'C': {
        const x1 = num(), y1 = num(), x2 = num(), y2 = num(), x = num(), y = num();
        for (let t = 1; t <= 16; t++) {
          const u = t / 16, m = 1 - u;
          cur.push([
            m*m*m*cx + 3*m*m*u*x1 + 3*m*u*u*x2 + u*u*u*x,
            m*m*m*cy + 3*m*m*u*y1 + 3*m*u*u*y2 + u*u*u*y,
          ]);
        }
        cx = x; cy = y; break;
      }
      case 'c': {
        const x1 = cx + num(), y1 = cy + num(), x2 = cx + num(), y2 = cy + num();
        const x = cx + num(), y = cy + num();
        for (let t = 1; t <= 16; t++) {
          const u = t / 16, m = 1 - u;
          cur.push([
            m*m*m*cx + 3*m*m*u*x1 + 3*m*u*u*x2 + u*u*u*x,
            m*m*m*cy + 3*m*m*u*y1 + 3*m*u*u*y2 + u*u*u*y,
          ]);
        }
        cx = x; cy = y; break;
      }
      case 'A': case 'a': {
        const rx = num(), ry = num(), rotDeg = num(), laf = num(), sf = num();
        let x = num(), y = num();
        if (cmd === 'a') { x += cx; y += cy; }

        if (rx === 0 || ry === 0) { push(x, y); break; }

        if (Math.abs(rx - ry) < 1e-6 && Math.abs(rotDeg) < 1e-6) {
          // 圆：直接用角度采样，避免端点参数化公式的符号陷阱。
          const mx = (cx + x) / 2, my = (cy + y) / 2;
          const dx = x - cx, dy = y - cy;
          const half = Math.hypot(dx, dy) / 2;
          const r = Math.max(rx, half);
          const h = Math.sqrt(Math.max(0, r * r - half * half));
          const ux = dx / (half * 2 || 1), uy = dy / (half * 2 || 1);
          // 两个候选圆心，按 large-arc/sweep 组合取正确的一个
          const sign = laf === sf ? 1 : -1;
          const ox = mx - sign * h * uy, oy = my + sign * h * ux;
          let a0 = Math.atan2(cy - oy, cx - ox);
          let a1 = Math.atan2(y - oy, x - ox);
          let delta = a1 - a0;
          if (sf === 1 && delta < 0) delta += 2 * Math.PI;
          if (sf === 0 && delta > 0) delta -= 2 * Math.PI;
          const steps = Math.max(8, Math.ceil(Math.abs(delta) / 0.08));
          for (let t = 1; t <= steps; t++) {
            const th = a0 + (delta * t) / steps;
            cur.push([ox + r * Math.cos(th), oy + r * Math.sin(th)]);
          }
          cx = x; cy = y;
          break;
        }

        // 椭圆/旋转：退回端点参数化实现
        const phi = (rotDeg * Math.PI) / 180;
        const dx2 = (cx - x) / 2, dy2 = (cy - y) / 2;
        const x1p = Math.cos(phi) * dx2 + Math.sin(phi) * dy2;
        const y1p = -Math.sin(phi) * dx2 + Math.cos(phi) * dy2;
        let rxs = rx * rx, rys = ry * ry;
        const lam = (x1p * x1p) / rxs + (y1p * y1p) / rys;
        if (lam > 1) { const s = Math.sqrt(lam); rxs *= s * s; rys *= s * s; }
        const sign = laf === sf ? -1 : 1;
        let n0 = rxs * rys - rxs * y1p * y1p - rys * x1p * x1p;
        if (n0 < 0) n0 = 0;
        const co = sign * Math.sqrt(n0 / (rxs * y1p * y1p + rys * x1p * x1p));
        const cxp = (co * Math.sqrt(rxs) * y1p) / Math.sqrt(rys);
        const cyp = (-co * Math.sqrt(rys) * x1p) / Math.sqrt(rxs);
        const ccx = Math.cos(phi) * cxp - Math.sin(phi) * cyp + (cx + x) / 2;
        const ccy = Math.sin(phi) * cxp + Math.cos(phi) * cyp + (cy + y) / 2;
        const ang = (ux, uy, vx, vy) => {
          const dot = ux * vx + uy * vy;
          const len = Math.hypot(ux, uy) * Math.hypot(vx, vy);
          let a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
          if (ux * vy - uy * vx < 0) a = -a;
          return a;
        };
        const theta1 = ang(1, 0, (x1p - cxp) / Math.sqrt(rxs), (y1p - cyp) / Math.sqrt(rys));
        let dTheta = ang(
          (x1p - cxp) / Math.sqrt(rxs), (y1p - cyp) / Math.sqrt(rys),
          (-x1p - cxp) / Math.sqrt(rxs), (-y1p - cyp) / Math.sqrt(rys),
        );
        if (!sf && dTheta > 0) dTheta -= 2 * Math.PI;
        if (sf && dTheta < 0) dTheta += 2 * Math.PI;
        const steps = Math.max(8, Math.ceil(Math.abs(dTheta) / 0.08));
        for (let t = 1; t <= steps; t++) {
          const th = theta1 + (dTheta * t) / steps;
          cur.push([
            ccx + Math.cos(phi) * Math.sqrt(rxs) * Math.cos(th) - Math.sin(phi) * Math.sqrt(rys) * Math.sin(th),
            ccy + Math.sin(phi) * Math.sqrt(rxs) * Math.cos(th) + Math.cos(phi) * Math.sqrt(rys) * Math.sin(th),
          ]);
        }
        cx = x; cy = y; break;
      }
      case 'Z': case 'z': cur.push([sx, sy]); polys.push(cur); cur = []; cx = sx; cy = sy; break;
      default: break;
    }
  }
  if (cur.length) polys.push(cur);
  return polys;
}

function fillPolys(w, h, subpaths, scale, color, alphaBuf, ss, evenOdd) {
  const W = w * ss, H = h * ss;
  const polys = subpaths.map((p) => p.map(([x, y]) => [x * scale * ss, y * scale * ss]));
  let minY = Infinity, maxY = -Infinity;
  for (const p of polys) for (const [, y] of p) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
  const y0 = Math.max(0, Math.floor(minY)), y1 = Math.min(H - 1, Math.ceil(maxY));
  for (let y = y0; y <= y1; y++) {
    const yc = y + 0.5, xs = [];
    for (const p of polys) {
      for (let k = 0; k < p.length; k++) {
        const [ax, ay] = p[k], [bx, by] = p[(k + 1) % p.length];
        if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) {
          xs.push({ x: ax + ((yc - ay) / (by - ay)) * (bx - ax), dir: by > ay ? 1 : -1 });
        }
      }
    }
    if (!xs.length) continue;
    xs.sort((a, b) => a.x - b.x);
    let wind = 0, cross = 0;
    for (let k = 0; k < xs.length - 1; k++) {
      wind += xs[k].dir;
      cross += 1;
      // fillType="evenOdd" 靠穿越次数奇偶判定，是"挖空"的唯一实现方式；
      // 缺了它，镂空形状会渲染成实心（曾导致单色图层变成一整块盾牌）。
      const inside = evenOdd ? cross % 2 === 1 : wind !== 0;
      if (!inside) continue;
      const xa = Math.max(0, Math.ceil(xs[k].x - 0.5)), xb = Math.min(W - 1, Math.floor(xs[k + 1].x - 0.5));
      for (let x = xa; x <= xb; x++) {
        const o = ((y / ss) | 0) * w * 4 + (((x / ss) | 0) * 4);
        alphaBuf[o] = color[0]; alphaBuf[o + 1] = color[1]; alphaBuf[o + 2] = color[2]; alphaBuf[o + 3] = 255;
      }
    }
  }
  return alphaBuf;
}

function boxDownscale(src, sw, sh, dw, dh) {
  const out = Buffer.alloc(dw * dh * 4);
  const fx = sw / dw, fy = sh / dh;
  for (let y = 0; y < dh; y++) {
    for (let x = 0; x < dw; x++) {
      let r = 0, g = 0, b = 0, n = 0;
      const x0 = Math.floor(x * fx), x1 = Math.min(sw, Math.ceil((x + 1) * fx));
      const y0 = Math.floor(y * fy), y1 = Math.min(sh, Math.ceil((y + 1) * fy));
      for (let yy = y0; yy < y1; yy++) {
        for (let xx = x0; xx < x1; xx++) {
          const o = (yy * sw + xx) * 4;
          r += src[o]; g += src[o + 1]; b += src[o + 2]; n++;
        }
      }
      const o = (y * dw + x) * 4;
      out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n; out[o + 3] = 255;
    }
  }
  return out;
}

function renderVector(xmlPath, outPath, size, bgHex, fromHiRes) {
  const xml = fs.readFileSync(xmlPath, 'utf8');
  const vb = xml.match(/viewportWidth="([\d.]+)"[\s\S]*?viewportHeight="([\d.]+)"/);
  const vw = parseFloat(vb[1]), vh = parseFloat(vb[2]);
  const paths = [...xml.matchAll(/<path([\s\S]*?)\/>/g)].map((m) => {
    const fill = (m[1].match(/fillColor="#([0-9A-Fa-f]{6,8})"/) || [, 'FF000000'])[1];
    const data = (m[1].match(/pathData="([^"]+)"/) || [, ''])[1];
    const a = fill.length === 8 ? parseInt(fill.slice(0, 2), 16) : 255;
    const hex = fill.length === 8 ? fill.slice(2) : fill;
    const evenOdd = /fillType="evenOdd"/.test(m[1]);
    return { color: [parseInt(hex.slice(0,2),16), parseInt(hex.slice(2,4),16), parseInt(hex.slice(4,6),16), a], polys: parsePath(data), evenOdd };
  });

  const ss = 3;
  const paint = (sizePx, buf) => {
    if (bgHex) {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(bgHex.slice(i - 1, i + 1), 16));
      for (let i = 0; i < sizePx * sizePx; i++) {
        buf[i * 4] = r; buf[i * 4 + 1] = g; buf[i * 4 + 2] = b; buf[i * 4 + 3] = 255;
      }
    }
    const scale = sizePx / Math.max(vw, vh);
    for (const p of paths) fillPolys(sizePx, sizePx, p.polys, scale, p.color, buf, ss, p.evenOdd);
  };

  if (fromHiRes) {
    // 先在 8x 尺寸渲染再盒式降采样：模拟真实设备把矢量缩到小图标后的模糊程度，
    // 而不是用超采样"作弊"得到比设备更清晰的结果。
    const hi = size * 8;
    const hiBuf = Buffer.alloc(hi * hi * 4);
    paint(hi, hiBuf);
    writePng(outPath, size, size, boxDownscale(hiBuf, hi, hi, size, size));
  } else {
    const buf = Buffer.alloc(size * size * 4);
    paint(size, buf);
    writePng(outPath, size, size, buf);
  }
  return { vw, vh, pathCount: paths.length };
}

const [,, xmlPath, outPath, sizeArg, bgArg, hiResArg] = process.argv;
const info = renderVector(xmlPath, outPath, parseInt(sizeArg || '256', 10), bgArg, hiResArg === 'hires');
console.log(JSON.stringify(info));
