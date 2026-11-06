#!/usr/bin/env bash
# 从真机导出桌面 APK（含 split APK），供 Mac 上静态分析。
#
# 用法：
#   bash scripts/pull_launcher_apk.sh
#   bash scripts/pull_launcher_apk.sh com.oppo.launcher
#   bash scripts/pull_launcher_apk.sh com.android.launcher ~/Desktop/realme-launcher
#   ADB=/path/to/adb bash scripts/pull_launcher_apk.sh   # 指定 adb
#
# 只做只读导出：不改手机上的任何设置、不安装、不重启、不操作 UI。
set -euo pipefail

PKG="${1:-com.android.launcher}"
OUT="${2:-$HOME/Desktop/realme-launcher}"

if [ -z "${ADB:-}" ]; then
  if [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  else
    ADB="$(command -v adb || true)"
  fi
fi
if [ -z "${ADB:-}" ] || [ ! -x "$ADB" ]; then
  echo "找不到 adb。装 Android SDK platform-tools，或用 ADB=/path/to/adb 指定。" >&2
  exit 1
fi

echo "adb : $ADB"
echo "包名: $PKG"
echo
echo "===== 1/5 设备状态 ====="
"$ADB" devices -l
state="$("$ADB" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  cat >&2 <<'TIP'

没有可用设备（state 不是 device）。逐项检查：
  · 设置 → 关于本机 → 版本信息 → 连点「版本号」7 次开启开发者选项
  · 其他设置 / 系统设置 → 开发者选项 → 打开「USB 调试」
  · 通知栏把 USB 模式从「仅充电」切成「传输文件(MTP)」
  · 解锁屏幕，手机上弹出的「允许 USB 调试」勾「一律允许」再确定
  · adb devices 显示 unauthorized：重插线后重新看弹窗；offline：重插或重启 adb
  · realme UI 的「USB 调试(安全设置)」只限制 adb install，pull 不需要开
TIP
  exit 2
fi

echo
echo "===== 2/5 当前默认桌面（用来核对包名）====="
"$ADB" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true

echo
echo "===== 3/5 查询 APK 路径 ====="
raw="$("$ADB" shell pm path "$PKG" | tr -d '\r')"
if [ -z "$raw" ]; then
  echo "pm path 无输出：包名不对或该包不存在，用上一步的输出核对后重试。" >&2
  exit 3
fi
printf '%s\n' "$raw"

mkdir -p "$OUT"
echo
echo "===== 4/5 导出到 $OUT ====="
n=0
while IFS= read -r line; do
  p="${line#package:}"
  if [ "$p" = "$line" ]; then continue; fi
  if [ -z "$p" ]; then continue; fi
  n=$((n + 1))
  dest="$OUT/$(basename "$p")"
  if [ -e "$dest" ]; then dest="$OUT/${n}-$(basename "$p")"; fi
  echo "  [$n] $p"
  "$ADB" pull "$p" "$dest" || echo "  !! pull 失败：$p（系统分区可能限制，见文末备选方案）" >&2
done <<< "$raw"
if [ "$n" -eq 0 ]; then
  echo "没有解析出任何 APK 路径" >&2
  exit 4
fi

echo
echo "===== 5/5 校验（有 classes*.dex 才是有效 APK）====="
python3 - "$OUT" <<'PY'
import os, sys, zipfile
d = sys.argv[1]
total = 0
for f in sorted(os.listdir(d)):
    if not f.endswith('.apk'):
        continue
    p = os.path.join(d, f)
    try:
        z = zipfile.ZipFile(p)
        dex = [x for x in z.namelist() if x.endswith('.dex')]
        print('  %-44s %6.1f MB  dex=%d %s' %
              (f, os.path.getsize(p) / 1048576, len(dex), dex[:4]))
        total += len(dex)
    except Exception as e:
        print('  %-44s 无效 APK：%s' % (f, e))
print('  合计 dex 文件：%d' % total)
PY

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/inspect_launcher_apk.py" ]; then
  echo
  echo "===== 静态分析（可反复重跑）====="
  python3 "$SCRIPT_DIR/inspect_launcher_apk.py" "$OUT" --all "$OUT/all-strings.txt" \
    || echo "  分析未完成：APK 可能不完整，把上面输出一起发来分析" >&2
fi

echo
echo "完成。目录：$OUT"
echo "把整个目录（或 all-strings.txt）交给分析方即可。"
echo
cat <<'TIP'
若 pull 被系统分区拒绝（某些 ROM 限制 adb 读 /system/priv-app），
可在手机上的 root 终端 / Termux 里直接复制到共享盘，再从手机拷到 Mac：
  su -c 'mkdir -p /sdcard/Download/realme-launcher; for p in $(pm path com.android.launcher | cut -d: -f2); do cp "$p" /sdcard/Download/realme-launcher/$(basename "$p"); done'
TIP
