#!/bin/bash
# 本地构建 — 与 build-ci.sh 同工艺, 但把 SDK 路径指向 vendor/android-build。
# 用法: ./build-local.sh   (仅本机验证用; CI 走 build-ci.sh)
#
# ⚠️ 本地没有 build-tools/34.0.0, 只有 bt/android-11 ⇒ d8/aapt2/apksigner 用 11 版。
#    32 位 d8 编 min-api 21 没问题 (CI 用 34 版出的包才是发布件)。
set -e
cd "$(dirname "$0")"

BT="../../vendor/android-build/bt/android-11"
PLAT="../../vendor/android-build/platforms/android-34/android.jar"
export PATH="/c/Program Files/Java/jdk-1.8/bin:$PATH"
OUT=build-local

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

LIBS=libs
CP="$PLAT;$LIBS/api-classes.jar;$LIBS/shared-classes.jar;$LIBS/provider-classes.jar;$LIBS/annotation.jar"

echo "== aidl =="
"$BT/aidl.exe" -o "$OUT/gen" aidl/com/zhiyaunhe/sgmbot/shizuku/IShellService.aidl

echo "== manifest (本地适配副本) =="
# ⚠️ 本地 aapt2 是 11 版, 不认 <property> (API 31+ 元素) ⇒ 报
#    "unexpected element <property> found in <manifest><application><service>"。
#    所以**只在构建副本里**精确删除那一处元素, 源文件不动。
#    精确做法: 按行匹配 "        <property " 开头那段, 不用贪婪正则
#    (上一次用 `\s*<property.*?/>` 把注释里的 <property> 字样也吞了, 连带
#     删掉了 FOREGROUND_SERVICE_SPECIAL_USE 权限声明 —— 教训: 改清单别用宽正则)。
python3 - "$OUT/AndroidManifest.xml" <<'PY'
import io, re, sys
d = io.open('AndroidManifest.xml', 'rb').read()
# 只匹配 service 下那个真实元素: 行首缩进 + <property, 到该元素 /> 结束
pat = rb'^[ \t]*<property\b[^>]*?/>\n'
new, n = re.subn(pat, b'', d, flags=re.M)
assert n == 1, '期望恰好删 1 处 <property>, 实际 %d' % n
# 自检: 权限声明必须还在 (上次被误删的那个)
assert b'FOREGROUND_SERVICE_SPECIAL_USE' in new, '误删了 FGS 权限声明!'
# versionCode 也必须抬高: CI 用 github.run_number 覆盖 (release 已是 26),
# 本地包若还是 1 会报 INSTALL_FAILED_VERSION_DOWNGRADE 装不上。
new = re.sub(rb'android:versionCode="[0-9]*"', b'android:versionCode="9999"', new)
new = re.sub(rb'android:versionName="[^"]*"', b'android:versionName="0.1.local"', new)
io.open(sys.argv[1], 'wb').write(new)
print('  本地副本: 删 <property> %d 处, versionCode→9999, 权限声明完好' % n)
PY

echo "== aapt2 =="
"$BT/aapt2.exe" compile --no-crunch --dir res -o "$OUT/res.zip"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$PLAT" \
    --manifest "$OUT/AndroidManifest.xml" -A assets "$OUT/res.zip"

echo "== javac =="
{ find src "$OUT/gen" libs/aidl-src -name '*.java'; } > "$OUT/sources.txt"
javac -encoding UTF-8 -classpath "$CP" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
    | iconv -f GBK -t UTF-8 || true
test -f "$OUT/classes/com/zhiyaunhe/sgmbot/MainActivity.class" || { echo "[!!] javac 失败"; exit 1; }

echo "== d8 =="
# ⚠️ 必须绕开 d8.bat: 它靠 ..\tools\lib\find_java.bat 定位 java.exe,
#    而 11 版 build-tools 没带那个文件 ⇒ 脚本第 29 行 `goto :EOF` **静默退出**,
#    表现是 exit 0 但 dex 目录空空 (排查了半天)。直接调 jar 最稳。
# ⚠️ 且不能把 annotation.jar 喂给 11 版 d8: 它是 Java 17(class 61) 编的,
#    老 d8 直接 `IllegalArgumentException: Unsupported class file major version 61`。
#    注解只在编译期用 (javac 那步的 CP 里已有), D8 这步不需要 ⇒ 这里不带它。
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 21 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt" \
    "$LIBS/api-classes.jar" "$LIBS/shared-classes.jar" "$LIBS/provider-classes.jar" 2>&1 | tail -5
test -f "$OUT/dex/classes.dex" || { echo "[!!] d8 没产出 classes.dex"; exit 1; }

echo "== pack =="
(cd "$OUT/dex" && zip -q ../base.apk classes.dex)
"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign =="
# 同样绕开 apksigner.bat (同一个 find_java.bat 依赖)
java -jar "$BT/lib/apksigner.jar" sign --ks debug.keystore --ks-pass pass:android \
    --key-pass pass:android --out "$OUT/sgmbot.apk" "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify "$OUT/sgmbot.apk"

echo "APK: $OUT/sgmbot.apk ($(stat -c%s "$OUT/sgmbot.apk") bytes)"
