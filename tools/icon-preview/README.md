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

## 从设计稿（Figma/SVG）取图形到自适应图标

外部 SVG（常见 24x24 viewBox）要放到 108x108 的自适应图标画布，**不能靠正则缩放**。
推荐流程：

```powershell
$node = "E:\nvm4w\nodejs\node.exe"
# 1) 规范化：把 path 拆成「命令独立成词 + 空格分隔参数」，消除省略分隔符的歧义
& $node tools/icon-preview/normalize-path.js "<pathData>" > norm.txt
# 2) 修复命令粘连：`a.82.82,...` 里的 a 会丢，需按参数个数恢复隐式重复命令
& $node tools/icon-preview/fix-normalized-path.js --file norm.txt > fixed.txt
# 3) 缩放并居中：scale 由安全区决定（见下），dx/dy 用于居中
& $node tools/icon-preview/scale-normalized-path.js --file fixed.txt 3.501 13.30 12.25 > scaled.txt
# 4) 校验圆弧 flag 未被破坏
& $node tools/icon-preview/validate-path.js <vector-drawable.xml>
```

**缩放系数怎么定**：自适应图标的安全区是直径约 **66** 单位（108 画布内），
图案超出会被启动器的圆形遮罩切掉。系数 = 66 ÷ 图形在原始画布中的较长边。
例如原图盾牌高 18.85 单位 → `66 / 18.85 ≈ 3.501`。

**居中**：`dx = 54 - 原图中心x × scale`，`dy` 同理。

## 已实现的 SVG 能力与限制

支持：`M m L l H h V v C c S s Q q T t A a Z z`、隐式命令重复、`fillType="evenOdd"` 挖空。

**不支持**：渐变、stroke 描边、`<group>` 变换、透明度混合。若矢量用到这些，
渲染结果会与实际不符，不要用它下结论。

**圆弧（A/a）渲染不可靠**：本工具的圆弧实现有一条实际路径上会画错。
因此**涉及圆弧的图形，最终必须用别的手段复核**——推荐 Chrome 无头渲染：

```powershell
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless --disable-gpu `
  --screenshot=out.png --window-size=240,240 "file:///path/to/preview.html"
```

把「原图」与「转换后的图」放进同一个 HTML 并排渲染，左右对照即可判断转换是否正确。

## 踩过的坑（改动本工具前务必知道）

1. **缺命令分支会静默死循环**。曾漏掉 `Q`/`T` 分支，读到时索引不前进导致挂死，
   产出的「预览图」其实是残缺的，据此误判了好几个设计稿。现已加 guard 计数守卫。
2. **隐式命令重复**。`l1,2 3,4` 等价于 `l1,2 l3,4`；不支持会丢掉后续坐标段。
3. **命令与数字粘连**。`a.82.82,0,0,0-.532.216` 里第二个 `a` 紧贴数字，
   朴素分词会把它当成数字 `.82`，命令丢失、后续参数全体错位。
4. **`fillType="evenOdd"` 必须实现**。它是「挖空」的唯一手段；缺失时镂空会渲染成实心。
   Android 13+ 主题化图标正是靠镂空保留辨识度。
5. **相对命令不能加位移**。`c`/`a`/`s`/`q` 的参数是相对上一位置的增量：
   缩放要乘，**偏移绝不能加**。给它们加偏移会把路径撕裂（曾导致几何完全变形）。
   只有绝对命令（大写 `M L C S Q T A H V`）才加偏移。
6. **圆弧参数不可整体平移**。`A` 的 7 个参数里只有最后两个是坐标；
   前五个是 `rx, ry, x-axis-rotation, large-arc-flag, sweep-flag`——
   半径按比例缩放、旋转角与两个 flag 必须原样保留，否则会把 0/1 写成 13.297 这种非法值。
