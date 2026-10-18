#!/bin/bash
# 发版脚本 —— 按版本拆分 GitHub Release。
#
#   tools/release.sh patch            # 1.0.0 → 1.0.1
#   tools/release.sh minor            # 1.0.0 → 1.1.0
#   tools/release.sh major            # 1.0.0 → 2.0.0
#   tools/release.sh 1.2.3            # 直接指定
#   tools/release.sh patch --push     # 改完顺带 push (main + tag), 由 CI 出包
#   tools/release.sh patch --dry-run  # 只看会做什么
#
# 它只做三件事, 不重复 CI 的活:
#   ① 改 phone/app/version.properties (唯一事实源) + 同步 AndroidManifest 默认值
#   ② 提交 chore(release): vX.Y.Z
#   ③ 打轻量 tag vX.Y.Z
# push 之后 .github/workflows/release-apk.yml 负责构建 + 建 release + 上传 apk/bundle。
#
# ⚠️ 不 --force 推、不改写历史。versionCode 只增不减 (改小会让已装机器装不上)。
set -e
cd "$(dirname "$0")/.."

VP=phone/app/version.properties
MF=phone/app/AndroidManifest.xml
PUSH=0; DRY=0; VER=""

for a in "$@"; do
  case "$a" in
    --push) PUSH=1 ;;
    --dry-run|-n) DRY=1 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) VER="$a" ;;
  esac
done

if [ -z "$VER" ]; then
  echo "用法: tools/release.sh <major|minor|patch|x.y.z> [--push] [--dry-run]"; exit 2
fi

[ -f "$VP" ] || { echo "[!!] 找不到 $VP"; exit 1; }

CUR=$(sed -n 's/^versionName=//p' "$VP" | tr -d '\r' | head -1)
CURCODE=$(sed -n 's/^versionCode=//p' "$VP" | tr -d '\r' | head -1)
MAJ=${CUR%%.*}; REST=${CUR#*.}; MIN=${REST%%.*}; PAT=${REST#*.}

case "$VER" in
  major) MAJ=$((MAJ+1)); MIN=0; PAT=0 ;;
  minor) MIN=$((MIN+1)); PAT=0 ;;
  patch) PAT=$((PAT+1)) ;;
  [0-9]*.[0-9]*.[0-9]*) MAJ=${VER%%.*}; R2=${VER#*.}; MIN=${R2%%.*}; PAT=${R2#*.} ;;
  *) echo "[!!] 版本参数不认: $VER (要 major/minor/patch 或 x.y.z)"; exit 2 ;;
esac

NEW="$MAJ.$MIN.$PAT"
CODE=$((MAJ*10000 + MIN*100 + PAT))

if [ -n "$CURCODE" ] && [ "$CODE" -le "$CURCODE" ]; then
  echo "[!!] 新 versionCode ($CODE) 不大于当前的 ($CURCODE) —— 拒绝降低 (会导致装不上)"
  exit 1
fi
if git rev-parse -q --verify "refs/tags/v$NEW" >/dev/null; then
  echo "[!!] tag v$NEW 已存在"; exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "[!!] 工作树不干净, 先提交或清理:"; git status --short; exit 1
fi

echo "版本: $CUR → $NEW   versionCode: $CURCODE → $CODE"
[ "$DRY" = 1 ] && { echo "(dry-run, 不做改动)"; exit 0; }

python - "$VP" "$MF" "$NEW" "$CODE" <<'PY'
import io, re, sys
vp, mf, name, code = sys.argv[1:5]
s = io.open(vp, encoding='utf-8').read()
s = re.sub(r'^versionName=.*$', 'versionName=' + name, s, flags=re.M)
s = re.sub(r'^versionCode=.*$', 'versionCode=' + code, s, flags=re.M)
io.open(vp, 'w', encoding='utf-8', newline='\n').write(s)

m = io.open(mf, encoding='utf-8').read()
m = re.sub(r'android:versionCode="[0-9]*"', 'android:versionCode="%s"' % code, m, count=1)
m = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % name, m, count=1)
io.open(mf, 'w', encoding='utf-8', newline='\n').write(m)
print('  version.properties / AndroidManifest 已更新')
PY

git add "$VP" "$MF"
git commit -m "chore(release): v$NEW" -m "versionCode $CODE

$CUR..$NEW 之间的改动见上一批 feat/fix 提交; 本次只抬版本号 + 打 tag,
构建与发布交给 .github/workflows/release-apk.yml。

Co-Authored-By: Space Bunny <noreply@workbuddy.ai>" \
  -- "$VP" "$MF" >/dev/null
git tag "v$NEW"

echo "已提交并打 tag v$NEW"
if [ "$PUSH" = 1 ]; then
  git push origin main
  git push origin "v$NEW"
  echo "已推送 — CI 正在出包, 进度: gh run watch   /  gh release view v$NEW"
else
  echo "下一步: git push origin main && git push origin v$NEW"
  echo "  或重跑: tools/release.sh $NEW --push   (已提交过会拒绝, 直接 manaul push 即可)"
fi
