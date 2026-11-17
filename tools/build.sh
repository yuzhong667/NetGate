#!/usr/bin/env bash
#
# NetGate —— 不依赖 Gradle / Android Studio 的构建脚本
#
# 直接调用 SDK 里的 aapt2 / aidl / javac / d8 / zipalign / apksigner 出包，
# 适合只装了命令行 SDK 的机器（本项目发布用的 APK 就是这样打出来的）。
#
# 用法：
#   ANDROID_SDK_ROOT=~/android-sdk ./tools/build.sh
#   ./tools/build.sh --sdk ~/android-sdk --version-name 2.6 --version-code 17
#
# 需要的 SDK 组件：platforms;android-34、build-tools;34.0.0（更新版本也行）
# Shizuku 的 4 个 AAR 会在首次构建时从 Maven Central 下载到 build/libs/。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
DIST="$ROOT/dist"

VERSION_NAME="${VERSION_NAME:-2.6}"
VERSION_CODE="${VERSION_CODE:-18}"
PKG="com.d.netgate"
AIDL="$APP/aidl/com/d/netgate/IUserService.aidl"
SHIZUKU_VER="13.1.5"

die() { echo "错误：$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --sdk)          ANDROID_SDK_ROOT="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    -h|--help)      sed -n '2,14p' "$0"; exit 0 ;;
    *)              die "未知参数 $1" ;;
  esac
done

# ---------------------------------------------------------------- SDK 定位
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK" ] || die "请设置 ANDROID_SDK_ROOT（或 ANDROID_HOME），或用 --sdk 指定"

if [ -f "$SDK/android.jar" ]; then
  TOOLS="$SDK"                       # 扁平目录：platform 与 build-tools 同层
  ANDROID_JAR="$SDK/android.jar"
else
  PLATFORM="${COMPILE_SDK:-34}"
  ANDROID_JAR="$SDK/platforms/android-$PLATFORM/android.jar"
  TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)}"
fi
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar，检查 platforms/android-34 是否安装"
[ -d "$TOOLS" ] || die "找不到 build-tools 目录"
for t in aapt2 aidl d8 zipalign apksigner; do
  [ -x "$TOOLS/$t" ] || die "build-tools 里缺少 $t"
done

mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

# ---------------------------------------------------------------- Shizuku 依赖
LIBS="$OUT/libs"
mkdir -p "$LIBS"
fetch_aar() {
  local name="$1"
  local aar="$LIBS/$name.aar"
  if [ ! -f "$aar" ]; then
    curl -fsSL -o "$aar" \
      "https://repo1.maven.org/maven2/dev/rikka/shizuku/$name/$SHIZUKU_VER/$name-$SHIZUKU_VER.aar" \
      || die "下载 Shizuku $name 失败，可手动放到 $LIBS/"
  fi
  [ -f "$LIBS/$name/classes.jar" ] || unzip -o -q "$aar" classes.jar -d "$LIBS/$name"
}
for a in api provider aidl shared; do fetch_aar "$a"; done
CP="$ANDROID_JAR:$LIBS/api/classes.jar:$LIBS/provider/classes.jar:$LIBS/aidl/classes.jar:$LIBS/shared/classes.jar"

# ---------------------------------------------------------------- 依赖清单
# 仓库里的 AndroidManifest 不含 package / 版本号（Gradle 用 namespace 提供），
# 这里在构建时补上，脚本与 Gradle 共用同一份清单。
MANIFEST="$OUT/AndroidManifest.xml"
sed -e "s|<manifest |<manifest package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\" |" \
    "$APP/AndroidManifest.xml" > "$MANIFEST"

echo "== aidl =="
"$TOOLS/aidl" -o "$OUT/gen" -I "$APP/aidl" "$AIDL"

echo "== aapt2 compile =="
cd "$APP"
while IFS= read -r f; do
  "$TOOLS/aapt2" compile -o "$OUT/flat" "$f"
done < <(find res -type f | sort)
cd "$ROOT"

echo "== aapt2 link =="
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -R "$OUT"/flat/*.flat \
  --java "$OUT/gen"

echo "== javac =="
javac -encoding UTF-8 --release 11 -classpath "$CP" \
  -d "$OUT/classes" $(find "$APP/java" "$OUT/gen" -name '*.java')

echo "== d8 =="
cd "$OUT/classes"
jar cf "$OUT/app.jar" .
"$TOOLS/d8" --release --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" \
  "$OUT/app.jar" "$LIBS/api/classes.jar" "$LIBS/provider/classes.jar" \
  "$LIBS/aidl/classes.jar" "$LIBS/shared/classes.jar"

echo "== package =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
zip -q "$OUT/unsigned.apk" classes.dex
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== sign =="
KS="$ROOT/keystore/netgate.jks"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  echo "  未找到签名密钥，生成一个仅供本机使用的（勿提交到仓库）"
  keytool -genkeypair -keystore "$KS" -alias netgate \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass netgate2026 -keypass netgate2026 \
    -dname "CN=NetGate, OU=Personal, O=NetGate, L=Shanghai, ST=Shanghai, C=CN" >/dev/null
fi

APK="$DIST/NetGate-$VERSION_NAME.apk"
"$TOOLS/apksigner" sign --ks "$KS" --ks-key-alias netgate \
  --ks-pass pass:netgate2026 --key-pass pass:netgate2026 \
  --out "$APK" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --print-certs "$APK" | head -3
ls -lh "$APK"
