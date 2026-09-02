"""连接本地 MuMu 模拟器（ADB）并验证截图能力。

用法:
    python tools/connect_mumu.py

优先自动探测模拟器（Toolkit.find_adb_devices，含 MuMu 12 的 EmulatorExtras 截图扩展），
探测不到时回退到显式配置的 MuMu adb 路径与端口。
成功后保存一张截图到 debug/ 目录。
"""
import ctypes
import os
import sys
from pathlib import Path


def _preload_msvcrt() -> None:
    """先加载 System32 的新版 VC 运行库，再让 MAA 的 DLL 解析依赖。

    anaconda 的 python.exe 所在目录带有 2020 年的旧版 msvcp140/vcruntime140，
    Windows 加载器解析依赖时优先搜索 exe 目录，MAA 的 opencv_world4_maa.dll
    绑到旧 CRT 后初始化失败（WinError 1114）。按模块名提前加载 System32 的
    新版 CRT，后续同名依赖会直接命中已加载的新库。必须在 import cv2/maa 之前调用。
    """
    sys32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")
    for name in ("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll"):
        path = os.path.join(sys32, name)
        if os.path.exists(path):
            ctypes.WinDLL(path)


_preload_msvcrt()

import cv2

from maa.controller import AdbController
from maa.toolkit import Toolkit

from pf_env import MUMU_ADB_PATH, MUMU_ADDRESS  # noqa: E402  本机参数 (config.json)

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def pick_mumu_device():
    """在自动探测结果中找 MuMu 的 16384 端口，找不到返回 None。"""
    devices = Toolkit.find_adb_devices()
    for dev in devices:
        if dev.address == MUMU_ADDRESS:
            print(f"[auto] 探测到模拟器: {dev.name} @ {dev.address}")
            return dev
    return None


def main() -> int:
    Toolkit.init_option(str(PROJECT_ROOT / "debug"))

    device = pick_mumu_device()
    if device is not None:
        controller = AdbController(
            adb_path=device.adb_path,
            address=device.address,
            screencap_methods=device.screencap_methods,
            input_methods=device.input_methods,
            config=device.config,
        )
    else:
        if not MUMU_ADB_PATH:
            print("[fail] 未探测到模拟器，且 config.json 未配置 adb_path"
                  "（参考 config.example.json）")
            return 1
        print(f"[auto] 未探测到 {MUMU_ADDRESS}，回退 config.json 显式配置")
        controller = AdbController(
            adb_path=MUMU_ADB_PATH,
            address=MUMU_ADDRESS,
        )

    controller.post_connection().wait()
    if not controller.connected:
        print("[fail] 连接失败")
        return 1
    adb_path = device.adb_path if device else MUMU_ADB_PATH
    address = device.address if device else MUMU_ADDRESS
    print(f"[ok] 已连接 {adb_path} @ {address}")
    print(f"[info] 分辨率: {controller.resolution}")

    image = controller.post_screencap().wait().get()
    out = PROJECT_ROOT / "debug" / "screenshot.png"
    cv2.imwrite(str(out), image)
    print(f"[ok] 截图已保存: {out} ({image.shape[1]}x{image.shape[0]})")

    return 0


if __name__ == "__main__":
    sys.exit(main())
