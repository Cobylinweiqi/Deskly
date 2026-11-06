#!/usr/bin/env bash
# 跨 ROM 单元测试入口。
#
# 为什么需要这个脚本：本机通过 HTTP_PROXY/HTTPS_PROXY 走本地代理，curl 认这两个变量，
# 但 Gradle 的构建进程不读环境变量——必须显式转成 -D 系统属性，否则拉依赖时
# 会报 "Remote host terminated the handshake"。测试 JVM 里的代理已由
# app/build.gradle.kts 的 tasks.withType<Test> 自动注入，这里只需管构建进程。
#
# 首次运行会下载 Robolectric 依赖与 android-all 镜像（约 100 MB+），之后可离线跑。
set -euo pipefail
cd "$(dirname "$0")/.."

proxy_args=()
raw="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -n "$raw" ]; then
    hostport="${raw#*://}"
    hostport="${hostport%%/*}"
    host="${hostport%:*}"
    port="${hostport##*:}"
    if [ -n "$host" ] && [ -n "$port" ]; then
        proxy_args+=(
            -Dhttp.proxyHost="$host" -Dhttp.proxyPort="$port"
            -Dhttps.proxyHost="$host" -Dhttps.proxyPort="$port"
        )
        echo "使用代理 ${host}:${port}"
    fi
fi

exec ./gradlew testDebugUnitTest --console=plain "${proxy_args[@]}" "$@"
