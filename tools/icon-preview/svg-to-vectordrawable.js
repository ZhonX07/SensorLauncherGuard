// 设计稿 SVG -> Android vector drawable（用真正的 XML 解析器，不用正则）
//
// 设计稿是描边式几何，而 vector drawable 原生支持描边，因此保留 stroke 不转填充。
// 渐变按 spec「flat solid fills」压成品牌三色。
'use strict';
const fs = require('fs');
const { XMLParser } = require('fast-xml-parser');

const SRC = process.argv[2], DST = process.argv[3];
const parser = new XMLParser({ ignoreAttributes: false, attributeNamePrefix: '@', isArray: (n) => ['g','path','circle','linearGradient','stop'].includes(n) });
const doc = parser.parse(fs.readFileSync(SRC, 'utf8'));
const svg = doc.svg;

// 渐变 id -> 品牌平色（按 LOGO-SPEC.md 的三色）
const GRAD = {
  orbitGradient:      '#4E4CC9', // 轨道：靛蓝
  gateGradient:       '#4E4CC9', // 闸门：靛蓝
  innerOrbitGradient: '#8F8BFF', // 内部轨道：淡紫
  permitGradient:     '#62E3BD', // 放行通道与箭头：薄荷
};
const flat = (v) => {
  if (typeof v !== 'string') return null;
  const m = /url\(#([^)]+)\)/.exec(v);
  if (m) {
    if (!GRAD[m[1]]) throw new Error('未映射的渐变: ' + m[1]);
    return GRAD[m[1]];
  }
  return v === 'none' ? null : v;
};

const paths = [];
const push = (a) => {
  if (!a || !a['@d']) return;
  paths.push({
    d: String(a['@d']).replace(/\s+/g, ' ').trim(),
    fill: flat(a['@fill']),
    stroke: flat(a['@stroke']),
    strokeWidth: a['@stroke-width'],
    cap: a['@stroke-linecap'],
    join: a['@stroke-linejoin'],
    evenOdd: a['@fill-rule'] === 'evenodd',
  });
};
// <circle> -> 两段圆弧构成的圆（vector drawable 没有 circle 元素）
const circleToPath = (c, inherit) => {
  const cx = +c['@cx'], cy = +c['@cy'], r = +c['@r'];
  push({
    '@d': `M ${cx - r} ${cy} a ${r} ${r} 0 1 0 ${2 * r} 0 a ${r} ${r} 0 1 0 ${-2 * r} 0 Z`,
    '@fill': c['@fill'] ?? inherit.stroke ?? inherit.fill,
    '@stroke': c['@stroke'],
    '@stroke-width': c['@stroke-width'] ?? inherit.strokeWidth,
    '@stroke-linecap': c['@stroke-linecap'] ?? inherit.cap,
    '@stroke-linejoin': c['@stroke-linejoin'] ?? inherit.join,
  });
};

function walk(node, inherit) {
  if (!node) return;
  const own = {
    fill: node['@fill'] ?? inherit.fill,
    stroke: node['@stroke'] ?? inherit.stroke,
    strokeWidth: node['@stroke-width'] ?? inherit.strokeWidth,
    cap: node['@stroke-linecap'] ?? inherit.cap,
    join: node['@stroke-linejoin'] ?? inherit.join,
  };
  if (node.path) for (const p of node.path) push({ ...p, '@fill': p['@fill'] ?? own.fill, '@stroke': p['@stroke'] ?? own.stroke, '@stroke-width': p['@stroke-width'] ?? own.strokeWidth, '@stroke-linecap': p['@stroke-linecap'] ?? own.cap, '@stroke-linejoin': p['@stroke-linejoin'] ?? own.join });
  if (node.circle) for (const c of node.circle) circleToPath(c, own);
  if (node.g) for (const g of node.g) walk(g, own);
}
walk(svg, { fill: null, stroke: null, strokeWidth: null, cap: null, join: null });

// 输出
const lines = ['<?xml version="1.0" encoding="utf-8"?>',
  '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
  '    android:width="108dp" android:height="108dp"',
  '    android:viewportWidth="1024" android:viewportHeight="1024">'];
for (const p of paths) {
  lines.push('    <path');
  // 只描边的路径必须省略 fillColor：Android 不接受 "none"
  if (p.fill) lines.push(`        android:fillColor="${p.fill}"`);
  if (p.evenOdd) lines.push('        android:fillType="evenOdd"');
  if (p.stroke) {
    lines.push(`        android:strokeColor="${p.stroke}"`);
    if (p.strokeWidth) lines.push(`        android:strokeWidth="${p.strokeWidth}"`);
    if (p.cap) lines.push(`        android:strokeLineCap="${p.cap}"`);
    if (p.join) lines.push(`        android:strokeLineJoin="${p.join}"`);
  }
  lines.push(`        android:pathData="${p.d}" />`);
}
lines.push('</vector>');
fs.writeFileSync(DST, lines.join('\n') + '\n', 'utf8');

// 自检：颜色分布
const byColor = {};
for (const p of paths) {
  const k = p.stroke || p.fill || '(无)';
  byColor[k] = (byColor[k] || 0) + 1;
}
console.error(`共 ${paths.length} 个 path`);
console.error('颜色分布: ' + JSON.stringify(byColor));
