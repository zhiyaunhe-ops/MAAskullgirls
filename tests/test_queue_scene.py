"""接力队列 + 场地绑定/识别 的单元回归 (2026-10-02)。

覆盖 2026-10-02 两个需求的可离线部分:
  1. ScoreStore 接力队列: set 去重/丢不存在场次、pop 先进先出、删除场次顺手清队列、
     落盘后新实例可载回 (打完自动接下一场的任务单不能因为重启丢掉);
  2. 场次 scene 字段: create/update/create_child 与 clean_scene 归一;
  3. PfBot 纯判定: parse_score_ocr (pf_scene 历史 OCR 坑回归) / _kw_hit (关键词命中
     口径, 短关键词全等防残串误命中) / _queue_next (正常结束接续, error 不接续)。

跑法 (必须 anaconda python —— pf_bot 要 cv2/MAA。import pf_bot 会经 pf_store 常规
初始化真 STORE (载入-回写, 内容等价, bot 每次启动同样如此); 测试自身的读写全部
打在 tmp 目录或桩上):
    C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_queue_scene.py -q
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

from pf_domain import clean_scene  # noqa: E402
from pf_storage import ScoreStore  # noqa: E402


def _null_logger(msg, level="info"):
    return None


class _StubLedger:
    """版本账本桩: 记账接口存在、什么都不写。"""

    def record(self, sid, after, before=None, *, op="update", jjc=None):
        return None

    def mark_deleted(self, sid, cfg, jjc=None):
        return None

    def ensure_baseline(self, sess):
        return None


def make_store(tmp_path: Path) -> ScoreStore:
    return ScoreStore(data_dir=tmp_path, logger=_null_logger,
                      version_ledger=_StubLedger(), jjc_ref=lambda: None)


# ---------- 场次 scene 字段 ----------

def test_scene_create_update_child(tmp_path):
    st = make_store(tmp_path)
    s = st.create("元素场", {"type": "element", "value": "dark"}, scene=" EYE OF THE STORM ")
    assert s["scene"] == "EYE OF THE STORM"          # clean_scene 去空白
    s2 = st.create("无绑定场", None)
    assert s2["scene"] is None
    st.update(s["id"], scene="medici")               # 大小写原样存 (匹配时再归一)
    assert st.get(s["id"])["scene"] == "medici"
    st.update(s["id"], scene="   ")                  # 空串 = 清除绑定
    assert st.get(s["id"])["scene"] is None
    st.update(s["id"], scene="GOLD RUSH")
    child = st.create_child(s["id"])
    assert child["scene"] == "GOLD RUSH"             # 子场次继承绑定
    rows = {r["id"]: r for r in st.list_sessions()}
    assert rows[s["id"]]["scene"] == "GOLD RUSH"


def test_clean_scene():
    assert clean_scene("  MEDICI  ") == "MEDICI"
    assert clean_scene("") is None
    assert clean_scene("   ") is None
    assert clean_scene(None) is None
    assert clean_scene(123) == "123"


# ---------- 接力队列 ----------

def test_queue_set_dedupe_and_prune(tmp_path):
    st = make_store(tmp_path)
    a = st.create("A", None)
    b = st.create("B", None)
    q = st.queue_set([a["id"], b["id"], a["id"], "ghost", a["id"]])
    assert q == [a["id"], b["id"]]                   # 去重 + 丢不存在场次
    assert st.queue_list() == [a["id"], b["id"]]


def test_queue_pop_fifo_and_empty(tmp_path):
    st = make_store(tmp_path)
    a = st.create("A", None)
    b = st.create("B", None)
    st.queue_set([a["id"], b["id"]])
    assert st.queue_pop() == a["id"]
    assert st.queue_pop() == b["id"]
    assert st.queue_pop() is None


def test_queue_persist_and_delete(tmp_path):
    st = make_store(tmp_path)
    a = st.create("A", None)
    b = st.create("B", None)
    st.queue_set([a["id"], b["id"]])
    # 新实例 (模拟进程重启) 载回队列
    st2 = make_store(tmp_path)
    assert st2.queue_list() == [a["id"], b["id"]]
    # 删除场次顺手清出队列
    st2.delete(a["id"])
    assert st2.queue_list() == [b["id"]]
    st3 = make_store(tmp_path)
    assert st3.queue_list() == [b["id"]]


# ---------- PfBot 纯判定 ----------

@pytest.fixture()
def bot():
    from pf_bot import PfBot
    return PfBot()          # __init__ 只建内存对象, 不碰模拟器


def test_parse_score_ocr_regressions(bot):
    # pf_scene 坑史回归 (见 pf_bot.parse_score_ocr 注释)
    assert bot.parse_score_ocr("") == -1                     # 读不出
    assert bot.parse_score_ocr("SCORE: 12,345,678") == 12345678
    assert bot.parse_score_ocr("SCORE: O") == 0              # 0 认成字母 O
    assert bot.parse_score_ocr("SCOREO") == 0                # 标签粘连
    assert bot.parse_score_ocr("SCORE: 6") == 0              # 个位数只可能是 0 读错
    assert bot.parse_score_ocr("ROgO0") == 0                 # 装饰区噪声
    assert bot.parse_score_ocr("900") == 900                 # 噪声但 ≥10 如实报
    assert bot.parse_score_ocr("SCORE 48,600,000") == 48600000


def test_kw_hit(bot):
    assert bot._kw_hit("MEDICI", "MEDICI SHAKEDOWN")
    assert bot._kw_hit("medici", "Diamond Blood Sport") is False   # 无关场不误命中
    assert bot._kw_hit("EYE OF THE STORM", "EYE OFTHE STORM")      # OCR 丢空格仍子串命中
    assert bot._kw_hit("M", "MEDICI SHAKEDOWN") is False           # 短关键词只认全等
    assert bot._kw_hit("M", "M")
    assert bot._kw_hit("", "MEDICI SHAKEDOWN") is False            # 空关键词永不命中


def test_queue_next_reasons(bot, monkeypatch):
    from pf_bot import STATE
    calls = []

    class StubStore:
        def queue_pop(self):
            calls.append(1)
            return "sid-next"

        def get(self, sid):
            return {"id": sid, "name": "下一场"}

    monkeypatch.setattr("pf_bot.STORE", StubStore())
    for reason, expect in (("goal", "sid-next"), ("manual", "sid-next"),
                           ("scene", "sid-next"), ("error", None), (None, None)):
        STATE.end_reason = reason
        assert bot._queue_next() == expect, reason
    assert len(calls) == 3            # error/None 不该碰队列
    assert STATE.end_reason is None   # 读过即清
