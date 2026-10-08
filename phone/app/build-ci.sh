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
CP="$PLAT:$LIBS/api-classes.jar:$LIBS/shared-classes.jar:$LIBS/provider-classes.jar:$LIBS/annotation.jar"

echo "== aidl =="
# 自有 UserService 接口 + Shizuku server 桩 (moe.shizuku.server.*, 源在 libs/aidl-src,
# Bundle/Intent/IBinder 框架声明在 libs/aidl-framework — 老 aidl 需显式 import 才能解析)
mkdir -p "$OUT/gen"
"$BT/aidl" -o "$OUT/gen" aidl/com/zhiyaunhe/sgmbot/shizuku/IShellService.aidl
# moe.shizuku.server 桩的 .java 已入库 (libs/aidl-src), 由本地 aidl 生成:
#   for f in libs/aidl-src/moe/shizuku/server/*.aidl; do
#     aidl -I libs/aidl-src -I libs/aidl-framework -o build/gen "$f"; done
# CI 的 aidl 是 34 版 (v2 语法), 与本地 11 版对 Bundle/Intent 解析行为不同 — 不在 CI 生成

echo "== manifest 语法自检 =="
# ⚠️ 加这步的原因 (2026-10-08 实际踩到): 注释写成 /* ... */ 时, XML 解析器不把它当
# 注释, 会把后面的元素一起吞掉, 报错只有一句 "not well-formed (invalid token)"
# 且**不带行号上下文**, 在 CI 上只能靠翻日志定位。这里先自检给出人话提示。
# 两道检查: ① 注释块内不得出现 --  (XML 规则, 出现即非法)
#           ② python 能解析整棵树 (兜住未闭合标签/吞元素这类)
python3 - <<'PY' || { echo "[!!] AndroidManifest.xml 语法非法 — 见上面提示"; exit 1; }
import io, re, sys
d = io.open('AndroidManifest.xml', 'rb').read()
bad = 0
for m in re.finditer(rb'<!--(.*?)-->', d, re.S):
    if b'--' in m.group(1):
        print('[!!] 注释块内含 "--" (XML 非法): ' + m.group(1)[:100].decode('utf-8', 'replace'))
        bad += 1
op, cl = len(re.findall(rb'<!--', d)), len(re.findall(rb'-->', d))
if op != cl:
    print('[!!] 注释块没闭合: <!-- %d 个, --> %d 个' % (op, cl)); bad += 1
print('注释 %d 块, 未闭合 %d' % (op, cl - op))
import xml.etree.ElementTree as ET
try:
    r = ET.parse('AndroidManifest.xml').getroot()
    print('[ok] XML 可解析, 元素总数 %d' % len(list(r.iter())))
except Exception as e:
    print('[!!] XML 解析失败: %s' % e); bad += 1
sys.exit(1 if bad else 0)
PY

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
"$BT/d8" --min-api 21 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt"     "$LIBS/api-classes.jar" "$LIBS/shared-classes.jar" "$LIBS/provider-classes.jar" "$LIBS/annotation.jar"

echo "== pack =="
(cd "$OUT/dex" && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign =="
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/sgmbot.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/sgmbot.apk"

echo "APK: $OUT/sgmbot.apk ($(stat -c%s "$OUT/sgmbot.apk") bytes)"
