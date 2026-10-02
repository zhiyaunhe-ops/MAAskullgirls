"""导航自愈回归 (2026-10-02): 结束残留的战斗/结算页不再是认路死区。

现场: 手动「结束」时战斗已在打, 游戏自己打完停在 VICTORY 结算页; 重开连刷后
认路只认 hub/大厅, 对结算页按了 240s "房子"超时 -> 用户看到"刷了一场就停了"。
本测试用桩帧序列断言修复后的行为:
  - 战斗中 (速度泡命中): 只等, 不点任何东西;
  - 结算页: 点 CONTINUE 脱离 (不点房子);
  - 全程认不出时: 仍按原来的房子兜底 (行为不回归)。

跑法 (anaconda python; 纯桩, 不连模拟器):
    C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_nav_recovery.py -q
"""
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

import pf_env  # noqa: E402
pf_env.preload_msvcrt()

try:
    import cv2  # noqa: F401,E402
    import maa  # noqa: F401,E402
except Exception:  # noqa: BLE001
    pytest.skip("需要带 cv2/MAA 的解释器 (anaconda python)", allow_module_level=True)

import pf_nav  # noqa: E402
from pf_bot import PfBot  # noqa: E402
from pf_env import STATE  # noqa: E402
from pf_nav import HOME_BTN  # noqa: E402


class _Job:
    def wait(self):
        return None


class _Ctrl:
    def __init__(self):
        self.clicks = []

    def post_click(self, x, y):
        self.clicks.append((int(x), int(y)))
        return _Job()


def _make_bot(monkeypatch, frames):
    bot = PfBot()
    it = iter(frames)
    cur = {"img": frames[0]}

    def snap(tag=""):
        try:
            cur["img"] = next(it)
        except StopIteration:
            pass
        return cur["img"]

    def match_tpl(img, template, roi=(0, 0, 0, 0), th=0.7):
        if img == "hub" and template == pf_nav.TPL_HUB_PLAY:
            return (640, 465)
        if img == "victory" and template == pf_nav.TPL_RESULT_CONTINUE:
            return (690, 630)
        return None

    bot.snap = snap
    bot.match_tpl = match_tpl
    bot.battle_speed_level = lambda img: 3 if img == "battle" else 0
    bot.find_modal_x_cv = lambda img: None
    bot.find_popup_x = lambda img: None
    ctrl = _Ctrl()
    bot.controller = ctrl
    monkeypatch.setattr(pf_nav.time, "sleep", lambda s: None)
    monkeypatch.setattr(pf_nav, "mumu_launch_game", lambda *a, **k: (True, ""))
    STATE.quit = False
    STATE.running = False
    bot._nav_running_at_entry = False
    return bot, ctrl


def test_nav_waits_battle_then_clicks_result(monkeypatch):
    """战斗残留 -> 只等; 结算页 -> 点 CONTINUE; 全程不按房子。"""
    bot, ctrl = _make_bot(monkeypatch, ["battle", "battle", "victory", "hub"])
    bot.goto_pf_hub(timeout=5.0)
    assert ctrl.clicks == [(690, 630)], f"应只点结算 CONTINUE, 实际 {ctrl.clicks}"


def test_nav_home_fallback_kept(monkeypatch):
    """认不出的界面 -> 仍按房子兜底 (原有行为不回归)。"""
    bot, ctrl = _make_bot(monkeypatch, ["battle", "blankscreen", "hub"])
    bot.goto_pf_hub(timeout=5.0)
    assert ctrl.clicks == [HOME_BTN], f"应只有房子兜底一次, 实际 {ctrl.clicks}"
