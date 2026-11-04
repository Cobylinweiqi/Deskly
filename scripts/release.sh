#!/usr/bin/env bash
#
# Deskly 发版脚本：改版本号 → 干净构建 → 主仓库发 Release → 官方模块库仓库发 Release。
#
# 用法：
#   ./scripts/release.sh <versionCode> <versionName> [更新说明...]
# 例：
#   ./scripts/release.sh 22 0.14.0 "修复图标重排错位"
#
# 前置：
#   - 已登录 gh（gh auth status 通过）且对两个仓库有推送权限
#   - 工作区处于要发布的提交上（本脚本会新建并推送一个 git tag）
#
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "用法: $0 <versionCode> <versionName> [更新说明...]" >&2
  echo "例:   $0 22 0.14.0 \"修复图标重排错位\"" >&2
  exit 1
fi

VERSION_CODE="$1"
VERSION_NAME="$2"
shift 2
RELEASE_NOTES="${*:-Deskly $VERSION_NAME}"

MAIN_REPO="Cobylinweiqi/Deskly"
MODULES_REPO="Xposed-Modules-Repo/io.github.cobylinweiqi.deskly"
TAG="${VERSION_CODE}-${VERSION_NAME}"
APK_PATH="app/build/outputs/apk/release/app-release.apk"
TMP_APK="/tmp/Deskly-${VERSION_NAME}.apk"
GRADLE_FILE="app/build.gradle.kts"

# 0. 环境检查
command -v gh >/dev/null 2>&1 || { echo "错误: 未找到 gh 命令" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "错误: gh 未登录，请先执行 gh auth login" >&2; exit 1; }
[ -f "$GRADLE_FILE" ] || { echo "错误: 找不到 $GRADLE_FILE" >&2; exit 1; }

# 官方模块库仓库的 description 必须非空，否则 indexer 完全跳过该仓库（且只能网页设置）
MODULES_DESC=$(gh api "repos/$MODULES_REPO" --jq '.description // ""' 2>/dev/null || echo "")
if [ -z "$MODULES_DESC" ]; then
  echo "⚠️  警告: $MODULES_REPO 的 description 为空，发版后不会被 LSPosed 在线仓库索引！" >&2
  echo "    请用浏览器打开 https://github.com/$MODULES_REPO ，点右侧 About 齿轮填写模块名后保存。" >&2
  echo "    （API 对 maintain 角色无权限设置 description，必须在网页操作）" >&2
fi

# 1. 修改 build.gradle.kts 的 versionCode / versionName
python3 - "$GRADLE_FILE" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import sys, re
path, vc, vn = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding='utf-8').read()
s2 = re.sub(r'versionCode = \d+', f'versionCode = {vc}', s)
s2 = re.sub(r'versionName = "[^"]*"', f'versionName = "{vn}"', s2)
if s2 == s:
    sys.stderr.write("警告: 未能在 build.gradle.kts 匹配到 versionCode/versionName，请手动确认\n")
open(path, 'w', encoding='utf-8').write(s2)
print(f"已更新 {path}: versionCode={vc} versionName={vn}")
PY

# 2. 干净构建
echo "==> 构建 release APK ..."
./gradlew clean assembleRelease
[ -f "$APK_PATH" ] || { echo "错误: 构建产物不存在: $APK_PATH" >&2; exit 1; }

# 3. 确认 tag 不存在
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "错误: tag $TAG 已存在，请确认版本号" >&2; exit 1
fi

# 4. 主仓库发版
echo "==> 主仓库发版 $TAG ..."
git tag "$TAG"
git push origin "$TAG"
gh release create "$TAG" "$APK_PATH" \
  --repo "$MAIN_REPO" \
  --title "$VERSION_NAME" \
  --notes "$RELEASE_NOTES"

# 5. 官方模块库仓库发版（被在线仓库索引的关键）
echo "==> 官方模块库仓库发版 $TAG ..."
cp "$APK_PATH" "$TMP_APK"
gh release create "$TAG" "$TMP_APK" \
  --repo "$MODULES_REPO" \
  --title "$VERSION_NAME" \
  --notes "Deskly $VERSION_NAME —— 小米桌面整理 LSPosed 模块。源码与说明见主仓库：https://github.com/$MAIN_REPO/releases/tag/$TAG"

echo ""
echo "完成！bot 将在 5~15 分钟内索引，之后在 LSPosed 客户端「在线仓库」搜 Deskly 即可更新。"
