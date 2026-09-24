"""Raw framebuffer 截屏通道 — 免录屏弹窗、零压缩二进制管道。

原理：shell 身份直接调 /system/bin/screencap，stdout 输出为
    [12/16 字节头: width, height, format(, colorspace)，各 uint32] + 整屏 RGBA
不经 PNG 编码。Android 8+ 头为 16 字节（多一个 colorspace），老版本 12 字节，
端序依设备（screencap 按本机字节序写出，ARM/x86 均为小端），自动识别。

两级通道:
  raw_once(adb, serial)  单帧：每次 fork 一次 screencap，约 80~200ms/帧
  RawStream              常驻单流 `while true; do screencap; done`：
                         免每帧 adb 往返，一般 3~10 FPS（受 screencap 冷启动开销限制）
更高帧率需 native binder（ISurfaceComposer::captureScreen）常驻 daemon，
见 README「性能分级与扩展」。
"""
from __future__ import annotations

import struct
import subprocess
import threading
import time

MAX_DIM = 16384  # w/h 合理上限（重同步判定用）


class ScreencapError(RuntimeError):
    pass


def _try_parse(frame: bytes):
    """返回 (w, h, fmt, header_len, endian)，解析不出返回 None。"""
    n = len(frame)
    for hdr in (16, 12):
        if n < hdr:
            continue
        for end in ("<", ">"):
            w, h, fmt = struct.unpack_from(end + "III", frame, 0)
            if 0 < w <= MAX_DIM and 0 < h <= MAX_DIM and hdr + w * h * 4 == n:
                return w, h, fmt, hdr, end
    return None


def parse_header(frame: bytes):
    r = _try_parse(frame)
    if r is None:
        raise ScreencapError(
            f"raw screencap 解析失败 ({len(frame):,} B)——输出非标准整屏 RGBA？")
    return r


def to_rgba(frame: bytes):
    """→ (np.ndarray RGBA [h,w,4], w, h)。numpy 按需导入。"""
    import numpy as np
    w, h, _, hdr, _ = parse_header(frame)
    arr = np.frombuffer(frame, np.uint8, count=w * h * 4, offset=hdr).reshape(h, w, 4)
    return arr, w, h


def to_bgr(frame: bytes):
    """→ cv2 BGR 图（可直接 cv2.imwrite / 模板匹配）。"""
    import cv2
    rgba, w, h = to_rgba(frame)
    return cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGR)


def raw_once(adb_path: str, serial: str | None = None,
             display_id: int | None = None, timeout: float = 10.0) -> bytes:
    """单帧 raw 截屏。返回原始字节（头 + RGBA）。"""
    cmd = [adb_path]
    if serial:
        cmd += ["-s", serial]
    cmd += ["exec-out", "screencap"]
    if display_id is not None:
        cmd[-1] += f" -d {display_id}"
    # ⚠️ **绝不加 `text=True`**: screencap 输出是整屏 RGBA 二进制。一旦让
    # subprocess 解码，读线程会在非法字节上抛 UnicodeDecodeError，帧全丢。
    # text=False 时 stderr 也保持字节，报错信息手动 decode 成文本。
    proc = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if proc.returncode != 0 or not proc.stdout:
        err = (proc.stderr or b"")[:200].decode("utf-8", "replace")
        raise ScreencapError(f"screencap 失败 rc={proc.returncode}: {err!r}")
    parse_header(proc.stdout)  # 快速失败
    return proc.stdout


class RawStream:
    """常驻截屏流：latest() 拿最新整帧（旧帧即弃，适合"截屏→识别→操作"循环节奏）。

    用法:
        s = RawStream(adb_path, serial); s.start()
        frame, w, h = s.latest(timeout=2.0)
        s.stop()
    """

    def __init__(self, adb_path: str, serial: str | None = None):
        self.adb_path = adb_path
        self.serial = serial
        self.header_len = 16
        self.endian = "<"
        self.frame_no = 0          # 单调递增，调用方据此差分算 FPS
        self.proc: subprocess.Popen | None = None
        self._thread: threading.Thread | None = None
        self._cond = threading.Condition()
        self._frame: bytes | None = None
        self._running = False
        self._dead = False          # 读线程已退出（正常结束或异常）
        self._error: Exception | None = None   # 读线程的异常，供 latest() 转述

    # ---------- 生命周期 ----------

    def start(self) -> None:
        if self._running:
            return
        self._dead = False
        self._error = None
        probe = raw_once(self.adb_path, self.serial)
        _, _, _, self.header_len, self.endian = parse_header(probe)
        cmd = [self.adb_path]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += ["exec-out", "while true; do screencap; done"]
        # ⚠️ 二进制管道: **不能**给 text/encoding —— 见 raw_once 同处注释。
        # stderr 必须丢弃: 让它继承父进程会往控制台喷 adb 的噪声。
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL)
        self._running = True
        self._thread = threading.Thread(target=self._reader, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self._thread:
            self._thread.join(timeout=3)
        self.proc = self._thread = None

    def __enter__(self) -> "RawStream":
        self.start()
        return self

    def __exit__(self, *exc) -> None:
        self.stop()

    # ---------- 取帧 ----------

    def latest(self, timeout: float | None = None) -> tuple[bytes, int, int]:
        """timeout=None: 取当前最新帧（无帧报错）；给了 timeout: 等一帧新的。"""
        if self._error is not None:
            # 流已因异常停摆，重试也不会自己好 —— 直接抛出真根因。
            raise ScreencapError(f"raw 流读线程已异常退出: {self._error}") from self._error
        with self._cond:
            if timeout is not None:
                target = self.frame_no
                deadline = time.monotonic() + timeout
                # _dead 也是退出条件: 读线程一死就没有新帧了，没必要空等到超时。
                while (self.frame_no <= target and not self._dead
                       and time.monotonic() < deadline):
                    self._cond.wait(max(0.0, deadline - time.monotonic()))
            if self._error is not None:
                raise ScreencapError(
                    f"raw 流读线程已异常退出: {self._error}") from self._error
            if self._frame is None:
                raise ScreencapError("流中尚无帧")
            w, h = parse_header(self._frame)[:2]
            return self._frame, w, h

    # ---------- 内部 ----------

    def _publish(self, frame: bytes) -> None:
        with self._cond:
            self._frame = frame
            self.frame_no += 1
            self._cond.notify_all()

    def _reader(self) -> None:
        f = self.proc.stdout
        buf = bytearray()
        try:
            while self._running:
                chunk = f.read(4096)
                if not chunk:
                    break
                buf += chunk
                while True:
                    if len(buf) < self.header_len:
                        break
                    w, h = struct.unpack_from(self.endian + "II", buf, 0)
                    if not (0 < w <= MAX_DIM and 0 < h <= MAX_DIM):
                        del buf[:1]  # 帧边界失步：滑窗逐字节重同步
                        if len(buf) > 4 * 1024 * 1024:
                            raise ScreencapError("raw 流失步且无法重同步")
                        continue
                    total = self.header_len + w * h * 4
                    if len(buf) < total:
                        break
                    frame = bytes(buf[:total])
                    del buf[:total]
                    self._publish(frame)
        except Exception as e:  # noqa: BLE001
            # 2026-09-24 事故: 线程里未捕获的异常只把 traceback 打到 stderr,
            # 调用方只看到「流中尚无帧」—— 症状离根因太远。记下来, latest() 转述。
            self._error = e
        finally:
            # 线程无论怎么退出都唤醒等在 latest() 里的调用方,
            # 否则它会一直等到自己的 timeout 才拿到一句含糊的「尚无帧」。
            with self._cond:
                self._dead = True
                self._cond.notify_all()


if __name__ == "__main__":
    # 独立自测：python scap.py [serial]
    import sys
    path = sys.argv[1] if len(sys.argv) > 1 else None
    adb = sys.argv[2] if len(sys.argv) > 2 else "adb"
    data = raw_once(adb, path)
    w, h, fmt, hdr, end = parse_header(data)
    print(f"[ok] {w}x{h} fmt={fmt} header={hdr}B endian={end} total={len(data):,}B")
