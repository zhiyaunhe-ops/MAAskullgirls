#!/usr/bin/env bash
# 设备 adb 包装器。
#
# 为什么需要它：本沙箱每次 Bash 调用都会重启 adb daemon（上个 daemon 被回收），
# 而 K70 Pro 走 mDNS 无线调试 ⇒ daemon 刚起来时 `adb devices` 是空的，
# 等几秒才出现；且序列号形如 `adb-XXXX (2)._adb-tls-connect._tcp` **含空格**，
# 用 `awk '{print $1}'` 会截成 `adb-XXXX` ⇒ "device not found"。
#
# 用法：  tools/dev.sh shell "ls /sdcard"   |   tools/dev.sh forward tcp:18791 tcp:8791
ADB="${ADB_BIN:-C:/Users/zhiya/WorkBuddy/2026-09-25-13-47-46/.workbuddy/binaries/platform-tools/adb.exe}"
PORT="${ADB_PORT:-5038}"
WAIT="${ADB_WAIT:-40}"
export MSYS_NO_PATHCONV=1

pick() {
  local i s
  for ((i = 0; i < WAIT; i++)); do
    # 第 2 个 tab 字段是状态，第 1 个字段可能含空格 ⇒ 必须按 tab 切
    s=$("$ADB" -P "$PORT" devices 2>/dev/null | awk -F'\t' 'NR > 1 && $2 == "device" { print $1; exit }')
    if [ -n "$s" ]; then printf '%s' "$s"; return 0; fi
    sleep 1
  done
  return 1
}

S=$(pick) || { echo "ERR: ${WAIT}s 内没等到在线设备 (adb -P $PORT devices)" >&2; exit 9; }
exec "$ADB" -P "$PORT" -s "$S" "$@"
