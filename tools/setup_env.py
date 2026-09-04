"""一键配置环境：依赖安装 + 资源下载 + 环境自检。

用法:
    python tools/setup_env.py [--with-vendor] [--mirror <前缀>]

    --with-vendor   额外下载 MAAFramework 官方包到 vendor/（运行非必需，仅供文档/样例参考）
    --mirror <前缀> GitHub 下载加速前缀，如 https://ghproxy.net/ （作用于 github/raw 域名）

已存在的文件自动跳过，可重复执行。
"""
import argparse
import importlib.metadata
import os
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "tools"))

MAAFW_VERSION = "5.12.3"
STATIC_DIR = PROJECT_ROOT / "tools" / "static"
OCR_DIR = PROJECT_ROOT / "assets" / "resource" / "base" / "model" / "ocr"
VENDOR_DIR = PROJECT_ROOT / "vendor"

GITHUB = "https://github.com"
RAW = "https://raw.githubusercontent.com"

DOWNLOADS = {
    "OCR rec 模型 (en_us)": (
        RAW + "/MaaXYZ/MaaCommonAssets/HEAD/OCR/ppocr_v4/en_us/rec.onnx",
        OCR_DIR / "rec.onnx", 7_000_000,
    ),
    "OCR det 模型 (zh_cn)": (
        RAW + "/MaaXYZ/MaaCommonAssets/HEAD/OCR/ppocr_v4/zh_cn/det.onnx",
        OCR_DIR / "det.onnx", 4_000_000,
    ),
    "OCR keys": (
        RAW + "/MaaXYZ/MaaCommonAssets/HEAD/OCR/ppocr_v4/en_us/keys.txt",
        OCR_DIR / "keys.txt", 100,
    ),
    "Chart.js": (
        "https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js",
        STATIC_DIR / "chart.umd.min.js", 150_000,
    ),
}
VENDOR_ZIP = (
    GITHUB + f"/MaaXYZ/MaaFramework/releases/download/v{MAAFW_VERSION}/"
    f"MAA-win-x86_64-v{MAAFW_VERSION}.zip"
)

FAILS = []


def step(name: str) -> None:
    print(f"\n=== {name} ===", flush=True)


def ok(msg: str) -> None:
    print(f"  [ok] {msg}", flush=True)


def warn(msg: str) -> None:
    print(f"  [警告] {msg}", flush=True)


def fail(msg: str) -> None:
    print(f"  [失败] {msg}", flush=True)
    FAILS.append(msg)


def download(url: str, dest: Path, min_size: int, mirror: str) -> None:
    if not str(dest.resolve()).startswith(str(PROJECT_ROOT.resolve())):
        raise ValueError(f"下载目标越界 (必须在项目内): {dest}")
    if dest.exists() and dest.stat().st_size >= min_size:
        ok(f"已存在，跳过: {dest.name} ({dest.stat().st_size:,} B)")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    if mirror:
        if not mirror.startswith("https://"):
            fail(f"mirror 必须以 https:// 开头: {mirror}")
            return
        if url.startswith(GITHUB) or url.startswith(RAW):
            url = mirror + url
    last_err = None
    for attempt in (1, 2, 3):
        try:
            print(f"  下载 {url} ...", flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            t0 = time.time()
            with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
                f.write(resp.read())
            size = dest.stat().st_size
            if size < min_size:
                raise IOError(f"文件过小 ({size} B)")
            ok(f"{dest.name} ({size:,} B, {time.time()-t0:.1f}s)")
            return
        except Exception as e:  # noqa: BLE001
            last_err = e
            warn(f"第 {attempt} 次尝试失败: {e}")
            time.sleep(2)
    fail(f"下载失败: {dest.name} ({last_err})")


def step_pip() -> None:
    step("Python 依赖")
    if sys.version_info < (3, 8):
        fail("需要 Python 3.8+")
        return
    ok(f"Python {sys.version_info.major}.{sys.version_info.minor}")
    need = False
    try:
        ver = importlib.metadata.version("MaaFw")
        if ver != MAAFW_VERSION:
            warn(f"MaaFw 版本 {ver} != {MAAFW_VERSION}")
            need = True
        else:
            ok(f"MaaFw {ver}")
    except importlib.metadata.PackageNotFoundError:
        warn("MaaFw 未安装")
        need = True
    for mod in ("numpy", "cv2"):
        try:
            importlib.import_module(mod)
            ok(f"{mod} 已安装")
        except ImportError:
            warn(f"{mod} 未安装")
            need = True
    if need:
        print("  执行 pip install -r requirements.txt ...", flush=True)
        r = subprocess.run([sys.executable, "-m", "pip", "install", "-r",
                            str(PROJECT_ROOT / "requirements.txt")])
        if r.returncode == 0:
            ok("依赖安装完成")
        else:
            fail("pip install 失败，请手动执行: pip install -r requirements.txt")


def step_crt() -> None:
    step("VC 运行库检查 (防 WinError 1114)")
    sys32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")
    missing = [n for n in ("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll")
               if not os.path.exists(os.path.join(sys32, n))]
    if missing:
        warn(f"System32 缺少 {missing}，请安装 VC++ 2015-2022 运行库 (x64)")
    else:
        ok("System32 新版 CRT 齐全（脚本运行时仍会预载，防 anaconda 旧 CRT 劫持）")


def step_vendor(with_vendor: bool, mirror: str) -> None:
    step("MAAFramework 官方包 vendor/ (可选)")
    if (VENDOR_DIR / "bin" / "MaaFramework.dll").exists():
        ok("已存在，跳过")
        return
    if not with_vendor:
        warn("未下载（运行非必需）。需要时加 --with-vendor")
        return
    zip_path = VENDOR_DIR / f"MAA-win-x86_64-v{MAAFW_VERSION}.zip"
    download(VENDOR_ZIP, zip_path, 50_000_000, mirror)
    if zip_path.exists() and zip_path.stat().st_size >= 50_000_000:
        print("  解压到 vendor/ ...", flush=True)
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(VENDOR_DIR)
        ok("解压完成")


def step_templates() -> None:
    step("模板资源检查")
    tpl_dir = PROJECT_ROOT / "assets" / "resource" / "base" / "image" / "pf"
    need = {"pf_continue_btn", "vs_fight_btn", "drag_hint", "energy_x", "streak_x",
            "options_x", "result_continue", "hub_play", "detail_stats",
            "srv_ok", "srv_retry", "pf_refresh_btn"}
    have = {p.stem for p in tpl_dir.glob("*.png")}
    missing = need - have
    if missing:
        fail(f"缺少模板: {missing}（assets/resource/base/image/pf/ 应随项目一起拷贝）")
    else:
        ok(f"{len(have)} 张模板齐全")


def step_connect() -> None:
    step("MuMu 模拟器连接测试 (警告级)")
    from pf_env import resolve_adb
    adb_str, _addr = resolve_adb()
    if not adb_str:
        warn("未找到 MuMu adb（确认模拟器已启动，或在 config.json 配置 adb_path）")
        return
    adb = Path(adb_str)
    try:
        subprocess.run([str(adb), "connect", "127.0.0.1:16384"],
                       capture_output=True, timeout=15)
        from pf_env import preload_msvcrt
        preload_msvcrt()
        from maa.controller import AdbController
        from maa.define import MaaAdbScreencapMethodEnum, MaaAdbInputMethodEnum
        from maa.toolkit import Toolkit
        Toolkit.init_option(str(PROJECT_ROOT / "debug"))
        c = AdbController(adb_path=str(adb), address=_addr,
                          screencap_methods=int(MaaAdbScreencapMethodEnum.Default),
                          input_methods=int(MaaAdbInputMethodEnum.Default))
        c.post_connection().wait()
        if c.connected:
            ok("MuMu 连接成功 (127.0.0.1:16384)")
        else:
            warn("连接失败：确认模拟器已启动、ADB 端口为 16384")
    except Exception as e:  # noqa: BLE001
        warn(f"连接测试异常: {e}（确认模拟器已启动）")


def main() -> int:
    ap = argparse.ArgumentParser(description="PF Bot 一键配置环境")
    ap.add_argument("--with-vendor", action="store_true", help="下载 MAAFramework 官方包到 vendor/")
    ap.add_argument("--mirror", default="", help="GitHub 下载加速前缀")
    args = ap.parse_args()

    print(f"项目根: {PROJECT_ROOT}")
    step_pip()
    step_crt()
    step("资源下载 (已存在自动跳过)")
    for name, (url, dest, min_size) in DOWNLOADS.items():
        print(f"--- {name} ---", flush=True)
        download(url, dest, min_size, args.mirror)
    step_vendor(args.with_vendor, args.mirror)
    step_templates()
    step_connect()

    print("\n=== 结果 ===")
    if FAILS:
        print(f"{len(FAILS)} 项失败:")
        for f in FAILS:
            print(f"  - {f}")
        print("修复后重新运行本脚本即可。")
        return 1
    print("环境就绪。启动: python tools/pf_bot.py  →  WebUI http://127.0.0.1:8787 点「开始」")
    return 0


if __name__ == "__main__":
    sys.exit(main())
