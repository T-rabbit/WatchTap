#!/usr/bin/env bash
# 用 Android SDK build-tools 直接从命令行构建 APK（不依赖 Gradle）
#
# 依赖环境变量：
#   JAVA_HOME      JDK 17+
#   ANDROID_JAR    android-37.2/android.jar（Android 17）
#   API_JAR        libxposed api 102 的 classes.jar
#   BUILD_TOOLS    build-tools 目录（含 aapt2/d8/zipalign/apksigner）
#
# 用法：./build_apk.sh [debug|release]
set -euo pipefail
MODE="${1:-debug}"

: "${JAVA_HOME:?set JAVA_HOME}"
: "${ANDROID_JAR:?set ANDROID_JAR}"
: "${API_JAR:?set API_JAR}"
: "${BUILD_TOOLS:?set BUILD_TOOLS}"

JDK_BIN="$JAVA_HOME/bin"
BT="$BUILD_TOOLS"

cd "$(dirname "$0")"
ROOT="$(pwd)"
OUT="$ROOT/build"
APP=app/src/main
PKG="dev.zenlink"
MIN_SDK=31
TARGET_SDK="${TARGET_SDK:-34}"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "==> 1/7 生成 R.java + 编译资源 (aapt2)"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code 11 --version-name "3.2.0" \
  "$OUT/res.zip"
echo "    base.apk 已生成（含 resources.arsc）"

echo "==> 2/7 编译 Java"
"$JDK_BIN/javac" -Xlint:none -nowarn -encoding UTF-8 \
  -source 17 -target 17 \
  -cp "$ANDROID_JAR:$API_JAR" \
  -d "$OUT/classes" \
  $(find app/src/main/java "$OUT/gen" -name '*.java')

echo "==> 3/7 打包 class 到 jar"
"$JDK_BIN/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .

echo "==> 4/7 dex (d8, min-api $MIN_SDK)"
"$BT/d8" --release --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  "$OUT/classes.jar"

echo "==> 5/7 组装 APK（classes.dex + META-INF/xposed/*）"
cd "$OUT"
cp base.apk unsigned.apk
cd "$ROOT"
( cd "$OUT" && "$JDK_BIN/jar" --update --file unsigned.apk -C dex classes.dex )
# META-INF 资源（java_init.list / module.prop / scope.list）
( cd "$APP/resources" && "$JDK_BIN/jar" --update --file "$OUT/unsigned.apk" META-INF )

echo "==> 6/7 zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 7/7 签名 (apksigner)"
KS="$ROOT/watchtap.jks"
if [ ! -f "$KS" ]; then
  echo "    生成自签名 keystore（仅测试用）"
  "$JDK_BIN/keytool" -genkeypair -v \
    -keystore "$KS" -storepass watchtap -keypass watchtap \
    -alias watchtap -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=WatchTap, OU=Dev, O=WatchTap, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:watchtap --key-pass pass:watchtap \
  --ks-key-alias watchtap \
  --min-sdk-version "$MIN_SDK" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT/watchtap-$MODE.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/watchtap-$MODE.apk" | head -5

echo
echo "✔ 构建完成: $OUT/watchtap-$MODE.apk"
"$JDK_BIN/jar" tf "$OUT/watchtap-$MODE.apk" | grep -E "classes.dex|META-INF/xposed|AndroidManifest" || true
ls -lh "$OUT/watchtap-$MODE.apk"
