"""Native-library bootstrap shared by MAA entry points and offline vision tools.

This module uses only the standard library. Import it and call preload_msvcrt()
before importing cv2 or maa; importing the module alone does not load any DLLs.
"""
import ctypes
import os
import sys
import threading

_CRT_LOCK = threading.Lock()
_CRT_READY = False
_CRT_HANDLES = []


def preload_msvcrt() -> None:
    """Load System32's VC runtime once, before anaconda's older DLLs can bind.

    Keep handles alive for the process lifetime. A load failure propagates and
    leaves initialization retryable, matching the previous startup behavior.
    Non-Windows tools do not require this bootstrap.
    """
    global _CRT_READY
    if sys.platform != "win32":
        return
    with _CRT_LOCK:
        if _CRT_READY:
            return
        sys32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")
        for name in ("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll"):
            path = os.path.join(sys32, name)
            if os.path.exists(path):
                _CRT_HANDLES.append(ctypes.WinDLL(path))
        _CRT_READY = True
