"""截取 MuMu 当前画面的小工具。

用法:
    python tools/screencap.py [输出路径]   # 默认 debug/pf/manual_当前时间.png
"""
import ctypes
import os
import sys
import time
from pathlib import Path


def _preload_msvcrt() -> None:
    """先加载 System32 新版 VC 运行库，避免 anaconda 旧 CRT 导致 MAA DLL 初始化失败。

    必须在 import cv2/maa 之前调用，详见 connect_mumu.py 同名函数。
    """
    sys32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")
    for name in ("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll"):
        path = os.path.join(sys32, name)
        if os.path.exists(path):
            ctypes.WinDLL(path)


_preload_msvcrt()

import cv2  # noqa: E402
from maa.controller import AdbController  # noqa: E402
from maa.toolkit import Toolkit  # noqa: E402

from pf_env import resolve_adb  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def connect() -> AdbController:
    Toolkit.init_option(str(PROJECT_ROOT / "debug"))
    adb_path, address = resolve_adb()
    if not adb_path:
        raise RuntimeError("未找到 MuMu adb: 请在 config.json 配置 adb_path "
                           "(参考 config.example.json) 或确认模拟器已启动")
    controller = AdbController(adb_path=adb_path, address=address)
    controller.post_connection().wait()
    if not controller.connected:
        raise RuntimeError("连接 MuMu 失败")
    return controller


def screencap(controller: AdbController):
    return controller.post_screencap().wait().get()


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else (
        PROJECT_ROOT / "debug" / "pf" / f"manual_{time.strftime('%H%M%S')}.png"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    controller = connect()
    image = screencap(controller)
    cv2.imwrite(str(out), image)
    print(f"[ok] {out} ({image.shape[1]}x{image.shape[0]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
