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
import time
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


def test_kw_hit_ocr_fuzzy(bot):
    """2026-10-02 实测: OCR 丢首字母 'RUNESANDZEROS'->'UNESANDZEROS', 单向子串
    匹配失效致居中失败 -> 结束场次。长名现在允许双向子串 + 高相似度。
    注: 'ONES AND ZEROS' 与 'RUNESANDZEROS' 是同一张卡的 OCR 噪声 (多读了 R),
    应当命中; 真·不同场之间零误报 (18 个已知场名两两互测, 全绿)。"""
    assert bot._kw_hit("RUNESANDZEROS", "UNESANDZEROS")             # 丢首字母仍命中
    assert bot._kw_hit("UNESANDZEROS", "RUNESANDZEROS")             # 反向同样
    assert bot._kw_hit("ONES AND ZEROS", "RUNESANDZEROS")           # 同卡噪声, 命中
    assert bot._kw_hit("MEDICI SHAKEDOWN", "COSTUMEPARTY") is False
    assert bot._kw_hit("GOLD RUSH", "BLOOD SPORT") is False
    assert bot._kw_hit("EVE OF THE STORM", "AGAINST THE WIND") is False
    # 相似度只对 >=8 字母长名生效: 短词 'NIGHT' vs 'MIGHT' 不因 ratio=0.8 误命中
    assert bot._kw_hit("NIGHT", "MIGHT") is False
    assert bot._kw_hit("GHOUL", "NIGHTS GHOUL")                    # 子串照常


def test_nav_abort_flip(bot):
    """协作式中止: running 相对本次导航进入时翻转即中止 (结束↔开始两个方向)。"""
    from pf_bot import STATE
    try:
        STATE.quit = False
        bot._nav_running_at_entry = True
        STATE.running = True
        assert bot._nav_abort() is False
        STATE.running = False                    # 开跑路径: 用户点结束
        assert bot._nav_abort() is True
        bot._nav_running_at_entry = False
        STATE.running = False
        assert bot._nav_abort() is False
        STATE.running = True                     # 待命扫描: 用户点开始
        assert bot._nav_abort() is True
        bot._nav_running_at_entry = None         # 未设基线 = 不中止
        assert bot._nav_abort() is False
        STATE.quit = True
        assert bot._nav_abort() is True
    finally:
        STATE.quit = False
        STATE.running = False
        bot._nav_running_at_entry = None


def test_same_card_ocr_flap(bot):
    f = bot._same_card
    assert f(("RUNESANDZEROS", 34672590), ("UNESANDZEROS", 34672590))   # 尾卡 OCR 抽风=同张
    assert f(("MEDICI SHAKEDOWN", 55), ("MEDICISHAKEDOWN", 55))         # 空格/标点抖动
    assert not f(("RUNESANDZEROS", 34672590), ("UNESANDZEROS", 34672591))  # 分不同=不同张
    assert not f(("A SHOT IN THE DARK", 0), ("COSTUMEPARTY", 0))        # 同分但名不像
    assert f(("", -1), ("", -1))                                        # 双读不出=到头
    assert not f(("", -1), ("MEDICI", 55))


def test_queue_next_only_goal_advances(bot, monkeypatch):
    """2026-10-02 用户口径: 手动「结束」绝不能解读成"换下一场" ——
    首版 manual/scene 也接续, 用户点结束后 bot 又去认路+扫描下一个场,
    观感就是"卡在扫描循环里出不来"。现在只有 goal 接续。"""
    from pf_bot import STATE

    class StubStore:
        session_id = "cur"

        def __init__(self):
            self.q = ["next"]
            self.popped = []

        def queue_pop(self):
            if not self.q:
                return None
            sid = self.q.pop(0)
            self.popped.append(sid)
            return sid

        def queue_list(self):
            return list(self.q)

        def get(self, sid):
            return {"id": sid, "name": sid}

    for reason in ("manual", "scene", "error", None):
        st = StubStore()
        monkeypatch.setattr("pf_bot.STORE", st)
        STATE.end_reason = reason
        assert bot._queue_next() is None, reason
        assert st.popped == [], f"{reason}: 不该消费队列"
        assert STATE.end_reason is None          # 读过即清
    st = StubStore()
    monkeypatch.setattr("pf_bot.STORE", st)
    STATE.end_reason = "goal"
    assert bot._queue_next() == "next"


def test_queue_next_skips_current_and_deleted(bot, monkeypatch):
    from pf_bot import STATE

    class StubStore:
        session_id = "cur"

        def __init__(self, q, known):
            self.q = list(q)
            self.known = set(known)
            self.popped = []

        def queue_pop(self):
            if not self.q:
                return None
            sid = self.q.pop(0)
            self.popped.append(sid)
            return sid

        def queue_list(self):
            return list(self.q)

        def get(self, sid):
            return {"id": sid, "name": sid} if sid in self.known else None

    # 队首即当前场次 -> 丢弃防重跑 (分数已达标的场次重跑会开场即"达标"成死循环)
    st = StubStore(["cur", "next"], {"cur", "next"})
    monkeypatch.setattr("pf_bot.STORE", st)
    STATE.end_reason = "goal"
    assert bot._queue_next() == "next"
    assert st.popped == ["cur", "next"]
    # 已删除的场次跳过, 剩余队列不搁置
    st = StubStore(["ghost", "next"], {"next"})
    monkeypatch.setattr("pf_bot.STORE", st)
    STATE.end_reason = "goal"
    assert bot._queue_next() == "next"
    assert st.popped == ["ghost", "next"]


def test_queue_discard(tmp_path):
    st = make_store(tmp_path)
    a = st.create("A", None)
    b = st.create("B", None)
    st.queue_set([a["id"], b["id"]])       # queue_set 本身去重
    assert st.queue_discard(a["id"]) == 1
    assert st.queue_list() == [b["id"]]
    assert st.queue_discard("ghost") == 0
    # 落盘后依旧生效 (重启不复活已剔除的场次)
    st2 = make_store(tmp_path)
    assert st2.queue_list() == [b["id"]]


# ---------- 场地绑定关键词 / 扫描录入 (2026-10-02 二段) ----------

def test_parse_scene_keyword():
    from pf_bot import PfBot
    p = PfBot.parse_scene_keyword
    assert p("#1") == ("index", 1)
    assert p(" #12 ") == ("index", 12)     # 位置绑定: 最左=1=月场, 不依赖 OCR
    assert p("#0") == ("index", 0)         # 解析照收, 语义拒绝在 center_scene
    assert p(" MEDICI ") == ("title", "MEDICI")
    assert p("12") == ("title", "12")      # 纯数字不是位置 (必须带 #)
    assert p("") == (None, None)
    assert p(None) == (None, None)
    assert p("#") == ("title", "#")        # 无数字 -> 当名字 (扫完报未找到)


def test_arenas_roundtrip_and_stale_day(bot, tmp_path):
    from pf_bot import STATE
    from pf_nav import game_day
    p = tmp_path / "arenas.json"
    STATE.arenas = {"day": game_day(), "ts": time.time(), "arenas": [
        {"idx": 0, "title": "MEDICI SHAKEDOWN", "score": 48600000},
        {"idx": 1, "title": "TRIAL BY FIRE", "score": 0}]}
    bot._save_arenas(p)
    loaded = bot._load_arenas(p)
    assert loaded == STATE.arenas          # 刷新线内回读无损 (含 ts)
    # 旧格式 (只有 day 无 ts): 游戏日相符仍可用 (平滑迁移)
    STATE.arenas = {"day": game_day(), "arenas": [{"idx": 0, "title": "OLD", "score": 1}]}
    bot._save_arenas(p)
    loaded = bot._load_arenas(p)
    assert loaded and loaded["arenas"] == STATE.arenas["arenas"]
    # 跨刷新线的旧格式作废
    STATE.arenas = {"day": "2020-01-01", "arenas": [{"idx": 0, "title": "OLD", "score": 1}]}
    bot._save_arenas(p)
    assert bot._load_arenas(p) is None
    # 损坏/缺失作废
    p.write_text("{not json", encoding="utf-8")
    assert bot._load_arenas(p) is None
    assert bot._load_arenas(tmp_path / "nope.json") is None


def test_arenas_freshness_1am_boundary():
    """扫描一天一次: 刷新线每天 01:00 (用户口径), 00:30 时仍算前一天。"""
    from datetime import datetime

    from pf_nav import arenas_fresh, game_day

    def ts(y, mo, d, h, mi=0):
        return datetime(y, mo, d, h, mi).timestamp()

    now = ts(2026, 10, 3, 10, 0)                       # 边界 = 10-03 01:00
    assert arenas_fresh({"ts": ts(2026, 10, 3, 1, 30)}, now)      # 今晨扫的 → 有效
    assert arenas_fresh({"ts": ts(2026, 10, 3, 1, 0)}, now)       # 恰在线上 → 有效
    assert not arenas_fresh({"ts": ts(2026, 10, 2, 23, 0)}, now)  # 昨晚扫的 → 过期
    now2 = ts(2026, 10, 3, 0, 30)                      # 边界 = 10-02 01:00 (还没到刷新点)
    assert arenas_fresh({"ts": ts(2026, 10, 2, 23, 0)}, now2)     # 昨晚 23:00 仍有效
    assert arenas_fresh({"ts": ts(2026, 10, 2, 1, 0)}, now2)
    assert not arenas_fresh({"ts": ts(2026, 10, 1, 23, 0)}, now2)
    # 无 ts 旧格式按游戏日字符串
    assert game_day(now2) == "2026-10-02" and game_day(now) == "2026-10-03"
    assert arenas_fresh({"day": "2026-10-02"}, now2)
    assert arenas_fresh({"day": "2026-10-03"}, now)
    assert not arenas_fresh({"day": "2026-10-02"}, now)
    # 坏数据
    assert not arenas_fresh(None) and not arenas_fresh({}) and not arenas_fresh("x")


def test_center_scene_index_validation(bot, monkeypatch):
    from pf_bot import STATE
    monkeypatch.setattr(STATE, "arenas",
                        {"day": "2026-10-02",
                         "arenas": [{"idx": 0, "title": "A", "score": 0},
                                    {"idx": 1, "title": "B", "score": 0}]},
                        raising=False)
    with pytest.raises(RuntimeError, match="#1 起"):
        bot.center_scene("#0")             # 位置从 1 起
    with pytest.raises(RuntimeError, match="没有第 3 个"):
        bot.center_scene("#3")             # 越界 (扫描只见 2 场)


# ---------- 连刷编排 sync_chain (2026-10-02 三段) ----------

SCAN = {"day": "2026-10-02", "arenas": [
    {"idx": 0, "title": "MEDICI SHAKEDOWN", "score": 48600000},
    {"idx": 1, "title": "TRIAL BY FIRE", "score": 0}]}


def test_sync_chain_creates_and_queues(tmp_path):
    from pf_webui import sync_chain
    st = make_store(tmp_path)
    logs = []
    out = sync_chain(st, [{"pos": 1, "title": "x", "target": 50000000},
                          {"pos": 2, "title": "y", "target": None}],
                     SCAN, enabled=True,
                     logger=lambda msg, level="info": logs.append(msg))
    assert [b["title"] for b in out] == ["MEDICI SHAKEDOWN", "TRIAL BY FIRE"]  # 标题随扫描
    assert out[0]["target"] == 50000000 and out[1]["target"] is None
    a, b = st.get(out[0]["sid"]), st.get(out[1]["sid"])
    assert a["scene"] == "#1" and a["score_target"] == 50000000
    assert b["scene"] == "#2" and b["score_target"] is None
    assert st.queue_list() == [out[0]["sid"], out[1]["sid"]]   # 链条顺序 = 接力队列


def test_sync_chain_reuses_slots_and_rotation(tmp_path):
    from pf_webui import sync_chain
    st = make_store(tmp_path)
    out = sync_chain(st, [{"pos": 1, "title": "x", "target": 100}], SCAN,
                     enabled=True, logger=lambda *a: None)
    sid1 = out[0]["sid"]
    rule_sess = st.get(sid1)
    st.update(sid1, rule={"type": "element", "value": "fire"})  # 人工配置
    # 次日轮换: 同位置换了新场, 槽位复用 + 标题/目标跟新, 人工配置不动
    scan2 = {"day": "2026-10-03", "arenas": [
        {"idx": 0, "title": "GOLD RUSH", "score": 0}]}
    out2 = sync_chain(st, [{"pos": 1, "title": "旧名", "target": 200, "sid": sid1}],
                      scan2, enabled=True, logger=lambda *a: None)
    assert out2[0]["sid"] == sid1
    s = st.get(sid1)
    assert s["name"] == "GOLD RUSH" and s["scene"] == "#1" and s["score_target"] == 200
    assert s["rule"] == {"type": "element", "value": "fire"}
    assert st.queue_list() == [sid1]


def test_sync_chain_disabled_clears_queue_and_bad_rows(tmp_path):
    from pf_webui import sync_chain
    st = make_store(tmp_path)
    out = sync_chain(st, [{"pos": 1, "title": "x", "target": None}], SCAN,
                     enabled=True, logger=lambda *a: None)
    assert st.queue_list() == [out[0]["sid"]]
    out2 = sync_chain(st, [{"pos": 0, "title": "bad", "target": None},
                           {"pos": "zz", "title": "bad2", "target": None}],
                      SCAN, enabled=False, logger=lambda *a: None)
    assert out2 == []                                   # 非法行丢弃
    assert st.queue_list() == []                        # enabled=false 清队列
    # 缺 sid 的场次被删后重建
    out3 = sync_chain(st, [{"pos": 1, "title": "x", "target": None, "sid": "ghost"}],
                      SCAN, enabled=True, logger=lambda *a: None)
    assert len(out3) == 1 and out3[0]["sid"] != "ghost" and st.get(out3[0]["sid"])
