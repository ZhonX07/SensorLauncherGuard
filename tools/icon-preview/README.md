# 图标预览工具

没有设计工具时，用来**实际看到**矢量图标渲染结果并做客观比对的脚本。
纯 Node.js，无第三方依赖。

## 为什么需要它

改图标时如果只改 `pathData` 数字就交付，很容易犯两类错误：

1. **小尺寸糊成一团**。自适应图标在启动器里可能只渲染到 24~48dp，笔画太细或内部
   标记太碎的图案在这个尺寸下会退化成噪点。原图标就是把陀螺仪画成「三横杠 + 上下圆点」，
   48px 下完全读不出是什么——这是图标观感差的主因。
2. **颜色撞车**。前景层与背景层若用同一个颜色，盾牌会整个消失。

这两类问题在代码里都看不出来，必须渲染出来看。

## 用法

```powershell
$node = "E:\nvm4w\nodejs\node.exe"

# 渲染单个矢量：<输入> <输出> <尺寸> <背景色> [hires]
& $node tools/icon-preview/svgraster.js `
    app/src/main/res/drawable/ic_launcher_foreground.xml `
    preview.png 192 E2DFFF

# 多尺寸对比图（192/96/48/24），每行一个设计
& $node tools/icon-preview/compare.js <输出目录> E2DFFF variants.json
#   variants.json: [{"name":"a","file":"/abs/path.xml"}, ...]

# 客观指标：墨迹占比、边缘密度（越低越清晰）、左右对称性
& $node tools/icon-preview/measure.js <输出目录> variants.json

# 解析器自检：确认四个基本形状（方块/圆/直角块/圆点）都能正确渲染
& $node tools/icon-preview/svgraster.js tools/icon-preview/selftest.xml st.png 144 FFFFFF
```

加 `hires` 参数会先在 8 倍尺寸渲染再盒式降采样，**逼近真实设备缩到小图标的模糊程度**；
不加则用超采样，结果会比设备更清晰，容易高估可辨识度。

## 已实现的 SVG 能力与限制

支持：`M m L l H h V v C c S s Q q T t A a Z z`、隐式命令重复、`fillType="evenOdd"` 挖空。

**不支持**：渐变、stroke 描边、`<group>` 变换、透明度混合。若矢量用到这些，
渲染结果会与实际不符，不要用它下结论。

## 踩过的坑（改动本工具前务必知道）

1. **缺命令分支会静默死循环**。曾漏掉 `Q` 分支，读到时 `i` 不前进导致挂死，
   产出的「预览图」其实是残缺的，据此误判了好几个设计稿。现已加 `guard`
   计数守卫，超过上限直接抛错。
2. **隐式命令重复**。`l1,2 3,4` 等价于 `l1,2 l3,4`；不支持会丢掉后续坐标段。
3. **`fillType="evenOdd"` 必须实现**。它是"挖空"的唯一手段；缺失时镂空形状
   会渲染成实心。
4. **圆弧要单独处理**。`rx == ry` 且无旋转时用角度直接采样，比通用端点参数化
   公式可靠；后者在符号上有陷阱，曾画出半圆缺失的盾牌。
