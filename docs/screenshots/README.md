# 文档渲染图生成

`platform-showcase.png` 不是在旧成品图上覆盖截图。每次更新都必须从三张原始窗口截图重新生成独立设备层，再按固定顺序合成；否则设备边框、阴影和前后遮挡会出错。

## 第一张渲染图

内容与尺寸固定：

- 桌面歌词页：窗口 `1280x800`，输出 `/tmp/qplayer-doc-desktop.png`。
- 平板设置页：窗口 `720x900`，输出 `/tmp/qplayer-doc-tablet.png`。
- 手机推荐页：窗口 `430x860`，输出 `/tmp/qplayer-doc-phone.png`。
- 最终画布：`1500x800`。

歌词页截图前先暂停，点击播放后等待封面恢复动画的 `500ms`，动画结束时立即截图。为覆盖点击和抓图本身的少量调度开销，自动化流程使用 `sleep 0.53`：

```sh
QPLAYER_WINDOW_ID="$(xdotool search --name '^QPlayer$' | head -n 1)"
xdotool windowsize "$QPLAYER_WINDOW_ID" 1280 800
# 播放按钮坐标以当次窗口为准；确认点击后图标已变为暂停。
xdotool mousemove --window "$QPLAYER_WINDOW_ID" 320 658
xdotool mousedown 1
sleep 0.06
xdotool mouseup 1
sleep 0.53
import -window "$QPLAYER_WINDOW_ID" /tmp/qplayer-doc-desktop.png
```

平板和手机截图只调整窗口尺寸并等待响应式布局稳定后抓取：

```sh
xdotool windowsize "$QPLAYER_WINDOW_ID" 720 900
sleep 0.8
import -window "$QPLAYER_WINDOW_ID" /tmp/qplayer-doc-tablet.png

xdotool windowsize "$QPLAYER_WINDOW_ID" 430 860
sleep 0.8
import -window "$QPLAYER_WINDOW_ID" /tmp/qplayer-doc-phone.png
```

使用 ImageMagick 生成圆角截图、设备框和软阴影：

```sh
magick /tmp/qplayer-doc-phone.png -resize 270x540! -alpha set \
  \( -size 270x540 xc:none -fill white -draw 'roundrectangle 0,0 269,539 26,26' \) \
  -compose DstIn -composite /tmp/qplayer-doc-phone-round.png
magick /tmp/qplayer-doc-tablet.png -resize 432x540! -alpha set \
  \( -size 432x540 xc:none -fill white -draw 'roundrectangle 0,0 431,539 20,20' \) \
  -compose DstIn -composite /tmp/qplayer-doc-tablet-round.png
magick /tmp/qplayer-doc-desktop.png -resize 1040x650! -alpha set \
  \( -size 1040x650 xc:none -fill white -draw 'roundrectangle 0,0 1039,649 14,14' \) \
  -compose DstIn -composite /tmp/qplayer-doc-desktop-round.png

magick -size 410x680 xc:none \
  \( -size 410x680 xc:none -fill '#0000009a' \
     -draw 'roundrectangle 60,60 349,619 36,36' -blur 0x26 \) \
  -compose Over -composite -fill '#0b0c11' -stroke '#ffffff30' -strokewidth 2 \
  -draw 'roundrectangle 60,60 349,619 36,36' /tmp/qplayer-doc-phone-frame.png
magick /tmp/qplayer-doc-phone-frame.png /tmp/qplayer-doc-phone-round.png \
  -geometry +70+70 -composite /tmp/qplayer-doc-phone-device.png

magick -size 572x680 xc:none \
  \( -size 572x680 xc:none -fill '#0000009a' \
     -draw 'roundrectangle 60,60 511,619 28,28' -blur 0x26 \) \
  -compose Over -composite -fill '#0b0c11' -stroke '#ffffff30' -strokewidth 2 \
  -draw 'roundrectangle 60,60 511,619 28,28' /tmp/qplayer-doc-tablet-frame.png
magick /tmp/qplayer-doc-tablet-frame.png /tmp/qplayer-doc-tablet-round.png \
  -geometry +70+70 -composite /tmp/qplayer-doc-tablet-device.png

magick -size 1180x790 xc:none \
  \( -size 1180x790 xc:none -fill '#0000009a' \
     -draw 'roundrectangle 60,60 1119,729 22,22' -blur 0x28 \) \
  -compose Over -composite -fill '#0b0c11' -stroke '#ffffff30' -strokewidth 2 \
  -draw 'roundrectangle 60,60 1119,729 22,22' /tmp/qplayer-doc-desktop-frame.png
magick /tmp/qplayer-doc-desktop-frame.png /tmp/qplayer-doc-desktop-round.png \
  -geometry +70+70 -composite /tmp/qplayer-doc-desktop-device.png
```

最后从背景开始，按“桌面、平板、手机”的顺序合成。这个顺序决定正确的前后遮挡关系：

```sh
magick -size 1500x800 gradient:'#24243a-#10141e' \
  \( -size 1500x800 xc:none \
     -fill '#455aa638' -draw 'ellipse 720,380 620,400 0,360' \
     -fill '#66517d24' -draw 'ellipse 1220,300 440,320 0,360' \
     -blur 0x105 \) \
  -compose screen -composite /tmp/qplayer-doc-showcase-bg.png

magick /tmp/qplayer-doc-showcase-bg.png \
  /tmp/qplayer-doc-desktop-device.png -geometry +160+5 -composite \
  /tmp/qplayer-doc-tablet-device.png -geometry +0+110 -composite \
  /tmp/qplayer-doc-phone-device.png -geometry +1090+115 -composite \
  -depth 8 -strip PNG32:docs/screenshots/platform-showcase.png
```

`docs/banner.svg` 提供基础图层。为避免 SVG 渲染器处理外链位图时出现差异，先渲染基础图层，再显式叠加第一张渲染图和图标：

```sh
magick docs/banner.svg -background none -depth 8 -strip PNG32:/tmp/qplayer-banner-base.png
magick docs/screenshots/platform-showcase.png -resize 712x380! -alpha set \
  \( -size 712x380 xc:none -fill white -draw 'roundrectangle 0,0 711,379 27,27' \) \
  -compose DstIn -composite /tmp/qplayer-banner-preview.png
magick docs/icon.png -resize 104x104 /tmp/qplayer-banner-icon.png
magick /tmp/qplayer-banner-base.png \
  /tmp/qplayer-banner-preview.png -geometry +520+130 -composite \
  /tmp/qplayer-banner-icon.png -geometry +64+76 -composite \
  -alpha off -colorspace sRGB -depth 8 -strip PNG24:docs/banner.png
```

## 第二张渲染图

第二张使用另一组页面和独立的 `1500x900` 布局：

- 桌面推荐页：窗口 `1280x800`，输出 `/tmp/qplayer-doc-second-desktop-home.png`。
- 平板歌词设置页：窗口 `720x900`，输出 `/tmp/qplayer-doc-second-tablet-settings.png`。
- 手机歌词页：窗口 `430x860`，输出 `/tmp/qplayer-doc-second-phone-lyrics.png`。

三张图应显示同一首歌曲。截图前暂停播放，使抓取不同页面时的进度保持一致。按第一张的方法调整窗口并抓取各页面，然后生成圆角截图：

```sh
magick /tmp/qplayer-doc-second-desktop-home.png -resize 960x600! -alpha set \
  \( -size 960x600 xc:none -fill white -draw 'roundrectangle 0,0 959,599 14,14' \) \
  -compose DstIn -composite /tmp/qplayer-doc-second-desktop-round.png
magick /tmp/qplayer-doc-second-tablet-settings.png -resize 432x540! -alpha set \
  \( -size 432x540 xc:none -fill white -draw 'roundrectangle 0,0 431,539 20,20' \) \
  -compose DstIn -composite /tmp/qplayer-doc-second-tablet-round.png
magick /tmp/qplayer-doc-second-phone-lyrics.png -resize 270x540! -alpha set \
  \( -size 270x540 xc:none -fill white -draw 'roundrectangle 0,0 269,539 26,26' \) \
  -compose DstIn -composite /tmp/qplayer-doc-second-phone-round.png
```

生成第二张专用的设备框：

```sh
magick -size 1100x740 xc:none \
  \( -size 1100x740 xc:none -fill '#0000009a' \
     -draw 'roundrectangle 60,60 1039,679 22,22' -blur 0x28 \) \
  -compose Over -composite -fill '#0b0c11' -stroke '#ffffff30' -strokewidth 2 \
  -draw 'roundrectangle 60,60 1039,679 22,22' /tmp/qplayer-doc-second-desktop-frame.png
magick /tmp/qplayer-doc-second-desktop-frame.png /tmp/qplayer-doc-second-desktop-round.png \
  -geometry +70+70 -composite /tmp/qplayer-doc-second-desktop-device.png

magick -size 572x680 xc:none \
  \( -size 572x680 xc:none -fill '#0000009a' \
     -draw 'roundrectangle 60,60 511,619 28,28' -blur 0x26 \) \
  -compose Over -composite -fill '#0b0c11' -stroke '#ffffff30' -strokewidth 2 \
  -draw 'roundrectangle 60,60 511,619 28,28' /tmp/qplayer-doc-second-tablet-frame.png
magick /tmp/qplayer-doc-second-tablet-frame.png /tmp/qplayer-doc-second-tablet-round.png \
  -geometry +70+70 -composite /tmp/qplayer-doc-second-tablet-device.png

magick -size 410x680 xc:none \
  \( -size 410x680 xc:none -fill '#0000009a' \
     -draw 'roundrectangle 60,60 349,619 36,36' -blur 0x26 \) \
  -compose Over -composite -fill '#0b0c11' -stroke '#ffffff30' -strokewidth 2 \
  -draw 'roundrectangle 60,60 349,619 36,36' /tmp/qplayer-doc-second-phone-frame.png
magick /tmp/qplayer-doc-second-phone-frame.png /tmp/qplayer-doc-second-phone-round.png \
  -geometry +70+70 -composite /tmp/qplayer-doc-second-phone-device.png
```

最后仍按“桌面、平板、手机”的顺序合成，但使用第二张自己的背景、尺寸和坐标：

```sh
magick -size 1500x900 gradient:'#202744-#211323' \
  \( -size 1500x900 xc:none \
     -fill '#7899ed30' -draw 'ellipse 490,470 560,440 0,360' \
     -fill '#e58bd031' -draw 'ellipse 1180,330 500,380 0,360' \
     -blur 0x110 \) \
  -compose screen -composite /tmp/qplayer-doc-second-bg.png

magick /tmp/qplayer-doc-second-bg.png \
  /tmp/qplayer-doc-second-desktop-device.png -geometry +0+100 -composite \
  /tmp/qplayer-doc-second-tablet-device.png -geometry +750+0 -composite \
  /tmp/qplayer-doc-second-phone-device.png -geometry +1090+210 -composite \
  -depth 8 -strip PNG32:docs/screenshots/platform-showcase-2.png
```
