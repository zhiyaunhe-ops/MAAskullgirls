#!/bin/bash
# SGM挂机 App CI/本地构建 — 与 vendor/android-build (pfwidget) 同工艺, 无 Gradle:
#   aapt2 compile/link → javac → d8 → zip classes.dex → zipalign → apksigner
# CI: ubuntu-latest 自带 ANDROID_HOME, workflow 里 sdkmanager 装 platforms;android-34
# 本地: 也可用 vendor/android-build 的 bt/platforms 换 SDK 变量跑
# 产物: build/sgmbot.apk (debug.keystore 签名 — 已入库, 保证可覆盖安装=可更新)
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

LIBS=libs
CP="$PLAT:$LIBS/api-classes.jar:$LIBS/aidl-classes.jar:$LIBS/shared-classes.jar:$LIBS/provider-classes.jar:$LIBS/annotation-1.7.1.jar"

echo "== aidl =="
mkdir -p "$OUT/gen"
"$BT/aidl" -o "$OUT/gen" aidl/com/zhiyaunhe/sgmbot/shizuku/IShellService.aidl

echo "== aapt2 compile/link =="
# --no-crunch: 模板 PNG 必须逐字节保真 (NCC 匹配对重编码敏感, 阈值 0.72 掉不起)
"$BT/aapt2" compile --no-crunch --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLAT" \
    --manifest AndroidManifest.xml -A assets "$OUT/res.zip"

echo "== javac =="
# libs/aidl-src: Shizuku AIDL 生成桩 (moe.shizuku.server.*) — api 构件只发 sources,
# 没有 classes jar, 故按源码编进 dex。缺它们时 Shizuku 类的静态字段
# SHIZUKU_APPLICATION 无法初始化, 触碰 Shizuku.* 即 NoClassDefFoundError (表现为闪退)。
{ find src "$OUT/gen" libs/aidl-src -name '*.java'; } > "$OUT/sources.txt"
javac -encoding UTF-8 -classpath "$CP" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 21 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt"     "$LIBS/api-classes.jar" "$LIBS/aidl-classes.jar" "$LIBS/shared-classes.jar" "$LIBS/provider-classes.jar" "$LIBS/annotation-1.7.1.jar"

echo "== pack =="
(cd "$OUT/dex" && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign =="
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/sgmbot.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/sgmbot.apk"

echo "APK: $OUT/sgmbot.apk ($(stat -c%s "$OUT/sgmbot.apk") bytes)"
