"""收藏聚合 + 评分 + 单文件炫耀页导出 (纯函数, 零设备 I/O)。

2026-10-14 用户口径: 扫描结果之上做「收藏统计」—— 把拥有的卡片都显示出来 + 计数
+ sgm 介绍 + 筛选 (和游戏内一样: 稀有度/元素/角色) + 导出炫耀页 + 收藏评分。

数据流:
    pf_roster 扫描结果 (cards: 每张一条, OCR 卡名)
      -> pf_fighters.variant_of() 纠错映射到变体闭集 (306 条)
      -> 本模块聚合: 同变体多张 = count (同名卡是**真副本**, 必须计数不能去重,
         见 2026-10-13 实测: 同屏 3 张 NUNSENSE / 11 张 UNDERDOG)
      -> 评分 + 单文件 HTML。

文案 (「sgm 的介绍」) 分层, 数据源 Krazete/sgm 仓库 data/en+zh-cn.json (2019 4.1.0
官方本地化, 已入库 tools/data/sgm_text_{en,zh}.json):
  - 官方覆盖 144/306 变体的 quote + SA 全文 —— 直接用;
  - 其余 162 条**没有官方文案可用** (Krazete 的 variants.json 就只有这 144 条,
    本地 306 条是超集, 共用字段零差异已核对) —— SA 名从 key 驼峰分词推导
    ('RitualSacrifice' -> 'Ritual Sacrifice'), 介绍标注「暂无官方介绍」。
  ⚠️ fandom wiki 的变体页没有独立条目 (2026-10-14 实测 Dark_Lord 页不存在),
     别走那条路。

评分口径 (公开可复算, 写进炫耀页):
  总分 = 100 x (0.5 x 完成度 + 0.3 x 稀有度 + 0.2 x 覆盖)
    完成度 = 独特变体数 / 306;  稀有度 = Σ变体权重 / 全图鉴满配权重
    (钻 8 / 金 4 / 银 2 / 铜 1, 满配 = 306 变体按各自稀有度加总);
    覆盖 = 有卡角色数 / 18。只算**独特变体** —— 重复卡不加分, 但在页面上可见。

本模块刻意只 import pf_fighters (同为纯查表), 不碰设备。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pf_fighters as fighters

_DATA = Path(__file__).resolve().parent / "data"

TIER_NAMES = {3: "钻石", 2: "黄金", 1: "白银", 0: "青铜"}
TIER_WEIGHTS = {3: 8, 2: 4, 1: 2, 0: 1}
GRADES = ((80, "S", "传说收藏家"), (65, "A", "大师收藏家"), (50, "B", "资深收藏家"),
          (35, "C", "进阶收藏家"), (20, "D", "见习收藏家"), (0, "E", "初出茅庐"))

_texts: dict | None = None


def _load_texts() -> dict:
    """懒加载官方文案表 (en/zh), 进程内缓存。"""
    global _texts
    if _texts is None:
        out = {}
        for lang in ("en", "zh"):
            p = _DATA / f"sgm_text_{lang}.json"
            try:
                out[lang] = json.loads(p.read_text(encoding="utf-8"))
            except OSError:
                out[lang] = {}
        _texts = out
    return _texts


def _sa_parts(entry: dict) -> list[dict]:
    """sa.features 里**有内容**的 feature (空 description + 空 values 的是占位)。"""
    feats = (entry.get("sa") or {}).get("features") or []
    return [f for f in feats if f.get("description")]


def _fill_desc(desc: str, feat: dict) -> str:
    """官方 desc 里 {0}/{1}/{0:0%} 占位符按**三级**数值填实; 填不了就原样返回。"""
    tiers = feat.get("tiers") or []
    vals = tiers[-1].get("values") if tiers else None
    if not vals:
        return desc
    try:
        return desc.format(*vals)
    except Exception:
        return desc


def sa_title(entry: dict) -> tuple[str, bool]:
    """(SA 能力名, 是否官方文案)。官方缺失时从 key 驼峰分词推导。"""
    title_key = (entry.get("sa") or {}).get("title") or ""
    texts = _load_texts()
    for lang in ("en", "zh"):
        hit = texts[lang].get(title_key)
        if hit:
            return hit.strip(), True
    # key 形如 Char_Eliza_SA_RitualSacrifice_Title -> 取 SA_ 与 _Title 之间那段
    m = re.search(r"_SA_(.+?)_Title$", title_key)
    if not m:
        return title_key or "(无签名能力)", False
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", m.group(1)), False


def variant_item(entry: dict, count: int = 1) -> dict:
    """变体条目 -> 前端/导出共用的展示结构。"""
    title, official = sa_title(entry)
    texts = _load_texts()
    feats = _sa_parts(entry)
    desc_en = texts["en"].get(feats[0]["description"], "") if feats else ""
    if desc_en and feats:
        desc_en = _fill_desc(desc_en, feats[0])
    stats = entry.get("stats") or [{}]
    last = stats[-1]
    quote_zh = texts["zh"].get(entry.get("quote") or "", "")
    quote_en = texts["en"].get(entry.get("quote") or "", "")
    return {
        "id": f"{entry.get('base')}|{entry.get('fandom')}",
        "fandom": entry.get("fandom") or "(未名)",
        "char": fighters.char_of_base(entry.get("base") or ""),
        "base": entry.get("base"),
        "element": entry.get("element"),
        "tier": entry.get("tier", 0),
        "count": count,
        "attack": last.get("attack"),
        "life": last.get("lifebar"),
        "sa_title": title,
        "sa_official": official,
        "sa_desc": desc_en or None,
        "quote_en": quote_en or None,
        "quote_zh": quote_zh or None,
    }


def aggregate(cards: list[dict]) -> dict:
    """扫描卡片 -> 变体聚合。cards 是 pf_roster.summary()['cards'] 的形态。

    ⚠️ 聚合 key 是**纠错后的官方卡名** (variant_of 的闭集映射), 不是 OCR 原文 ——
    'ICYHOT'/'IYHOT' 这类 OCR 噪声在映射时就被吸收, 不会裂成两个变体。
    """
    seen: dict[str, dict] = {}
    unmapped: list[str] = []
    for c in cards:
        entry, _how = fighters.variant_of(c.get("name") or "")
        if entry is None:
            unmapped.append(c.get("name") or "")
            continue
        item = seen.get(entry["fandom"])
        if item is None:
            item = variant_item(entry)
            seen[entry["fandom"]] = item
        else:
            item["count"] += 1
    variants = sorted(seen.values(), key=lambda v: (-v["tier"], v["char"] or "", v["fandom"]))
    by_tier = {str(t): sum(1 for v in variants if v["tier"] == t) for t in (3, 2, 1, 0)}
    by_element: dict[str, int] = {}
    for v in variants:
        k = str(v["element"])
        by_element[k] = by_element.get(k, 0) + 1
    by_char: dict[str, int] = {}
    for v in variants:
        k = v["char"] or "(未识别)"
        by_char[k] = by_char.get(k, 0) + 1
    return {
        "total_cards": len(cards),
        "variant_kinds": len(variants),
        "unmapped": unmapped,
        "by_tier": by_tier,
        "by_element": by_element,
        "by_char": by_char,
        "variants": variants,
    }


def full_weight() -> int:
    """全图鉴 306 变体的满配权重 (稀有度分母)。"""
    idx = fighters._load()
    return sum(TIER_WEIGHTS.get(e.get("tier"), 1) for e in idx["entry_by_name"].values())


def score(agg: dict) -> dict:
    """收藏评分 (口径见模块 docstring)。"""
    n_full = fighters.loaded_size()
    w = sum(TIER_WEIGHTS.get(v["tier"], 1) for v in agg["variants"])
    chars = {v["char"] for v in agg["variants"] if v["char"]}
    completeness = agg["variant_kinds"] / n_full if n_full else 0.0
    rarity = w / full_weight() if full_weight() else 0.0
    coverage = len(chars) / len(fighters.CHAR_DISPLAY) if fighters.CHAR_DISPLAY else 0.0
    total = round(100 * (0.5 * completeness + 0.3 * rarity + 0.2 * coverage), 1)
    for cut, letter, title in GRADES:
        if total >= cut:
            grade = (letter, title)
            break
    return {
        "total": total,
        "grade": grade[0],
        "grade_title": grade[1],
        "completeness": round(completeness * 100, 1),
        "rarity": round(rarity * 100, 1),
        "coverage": round(coverage * 100, 1),
        "chars_covered": len(chars),
    }


# ---------- 炫耀页导出 ----------
# 单文件 HTML: 数据内嵌、零外部请求, 发给谁都能直接双击打开。
# ⚠️ JSON 内嵌前必须转义 '</' —— 防止文案里出现 </script> 打断脚本。

_HTML_TMPL = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__ 的 Skullgirls Mobile 收藏</title>
<style>
:root{--bg:#14161d;--panel:#1d2029;--panel2:#242836;--line:#333848;--txt:#e8e6df;--dim:#8b90a0;--gold:#ffd54a;--dia:#8fd4ff;}
*{box-sizing:border-box;margin:0;padding:0}
body{background:radial-gradient(1200px 500px at 50% -100px,#252b40,var(--bg));color:var(--txt);font:14px/1.6 "Segoe UI",system-ui,sans-serif;padding:28px 18px 60px}
.wrap{max-width:1180px;margin:0 auto}
header{text-align:center;margin-bottom:26px}
header h1{font-size:26px;letter-spacing:2px;color:var(--gold);text-shadow:0 2px 12px rgba(255,213,74,.25)}
header .sub{color:var(--dim);font-size:12px;margin-top:6px}
.badge{display:inline-flex;align-items:center;gap:14px;background:linear-gradient(135deg,#2a2f42,#1d2029);border:1px solid var(--line);border-radius:16px;padding:16px 28px;margin:18px auto 8px;box-shadow:0 8px 30px rgba(0,0,0,.35)}
.badge .letter{font-size:52px;font-weight:800;line-height:1}
.t-S .letter{color:var(--dia)}.t-A .letter{color:#b08cff}.t-B .letter{color:var(--gold)}.t-C .letter{color:#9fd48a}.t-D .letter{color:#d29b6a}.t-E .letter{color:var(--dim)}
.badge .words{text-align:left}
.badge .words b{font-size:19px}
.badge .words span{display:block;color:var(--dim);font-size:12px}
.score{font-size:34px;font-weight:800;color:var(--txt)}
.bars{display:flex;gap:14px;justify-content:center;flex-wrap:wrap;margin:14px 0 26px}
.bar{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:10px 16px;min-width:200px;flex:1;max-width:280px}
.bar label{font-size:12px;color:var(--dim);display:flex;justify-content:space-between}
.bar progress{width:100%;height:8px;margin-top:6px}
.tiers{display:flex;gap:10px;justify-content:center;margin-bottom:18px;flex-wrap:wrap}
.tiers span{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:4px 14px;font-size:13px}
.filters{display:flex;gap:8px;justify-content:center;flex-wrap:wrap;margin-bottom:16px}
.filters button,.filters select{background:var(--panel2);border:1px solid var(--line);color:var(--txt);border-radius:999px;padding:5px 14px;font-size:13px;cursor:pointer}
.filters button.on{border-color:var(--gold);color:var(--gold)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px}
.card{background:var(--panel);border:1px solid var(--line);border-left-width:4px;border-radius:10px;padding:10px 12px;position:relative;min-height:96px}
.card .cnt{position:absolute;top:8px;right:10px;background:var(--panel2);border:1px solid var(--line);border-radius:999px;font-size:11px;padding:1px 8px;color:var(--dim)}
.card b{font-size:14px}
.card .ch{color:var(--dim);font-size:12px}
.card .sa{font-size:12px;color:#c9b27c;margin-top:4px}
.card .st{font-size:11px;color:var(--dim);margin-top:2px}
.card .q{font-size:11px;color:var(--dim);margin-top:6px;font-style:italic;border-top:1px dashed var(--line);padding-top:6px}
.t3{border-left-color:var(--dia)}.t2{border-left-color:var(--gold)}.t1{border-left-color:#cfd6de}.t0{border-left-color:#d29b6a}
.t3 .rk{color:var(--dia)}.t2 .rk{color:var(--gold)}.t1 .rk{color:#cfd6de}.t0 .rk{color:#d29b6a}
.rk{font-size:11px;margin-left:6px}
.empty{color:var(--dim);text-align:center;padding:40px}
footer{text-align:center;color:var(--dim);font-size:11px;margin-top:34px}
</style>
</head>
<body class="t__GRADE__">
<div class="wrap">
<header>
  <h1>Skullgirls Mobile 收藏图鉴</h1>
  <div class="badge">
    <div class="letter">__GRADE__</div>
    <div class="words"><b>__GRADE_TITLE__</b><span>收藏评分</span></div>
    <div class="score">__TOTAL__</div>
  </div>
  <div class="sub">__DATE__ · __TOTAL_CARDS__ 张卡 · __KINDS__ 个变体 · __CHARS__ / 18 个角色</div>
</header>
<div class="bars">
  <div class="bar"><label>完成度 <i>__C__%</i></label><progress value="__C__" max="100"></progress></div>
  <div class="bar"><label>稀有度 <i>__R__%</i></label><progress value="__R__" max="100"></progress></div>
  <div class="bar"><label>角色覆盖 <i>__V__%</i></label><progress value="__V__" max="100"></progress></div>
</div>
<div class="tiers">
  <span>💎 钻石 __T3__</span><span>🟡 黄金 __T2__</span><span>⚪ 白银 __T1__</span><span>🟤 青铜 __T0__</span>
</div>
<div class="filters" id="filters"></div>
<div class="grid" id="grid"></div>
<div class="empty" id="empty" hidden>该筛选下没有卡片</div>
<footer>由 PF bot 扫描生成 · 评分 = 50% 完成度 + 30% 稀有度 + 20% 角色覆盖 · 数据源 SGM 图鉴 306 变体</footer>
</div>
<script>
const DATA=__DATA__;
const TIER={3:"钻石",2:"黄金",1:"白银",0:"青铜"};
let fTier=-1, fChar="";
const chars=[...new Set(DATA.variants.map(v=>v.char))].sort();
const fl=document.getElementById("filters");
function chip(txt,fn,on){const b=document.createElement("button");b.textContent=txt;b.className=on?"on":"";b.onclick=()=>{fn();render()};fl.appendChild(b);return b}
chip("全部",()=>{fTier=-1;fChar=""},fTier===-1&&fChar==="");
for(const t of [3,2,1,0]) chip(TIER[t],()=>{fTier=fTier===t?-1:t;fChar=""},fTier===t);
const sel=document.createElement("select");
sel.add(new Option("全部角色",""));
for(const c of chars) sel.add(new Option(c,c));
sel.value=fChar; sel.onchange=()=>{fChar=sel.value;render()};
fl.appendChild(sel);
function render(){
  const g=document.getElementById("grid"); g.innerHTML="";
  const vs=DATA.variants.filter(v=>(fTier<0||v.tier===fTier)&&(!fChar||v.char===fChar));
  document.getElementById("empty").hidden=vs.length>0;
  for(const v of vs){
    const d=document.createElement("div");
    d.className="card t"+v.tier;
    const q=v.quote_zh||v.quote_en||v.sa_desc||"";
    d.innerHTML=`<span class="cnt">×${v.count}</span><b>${v.fandom}</b><span class="rk">[${TIER[v.tier]}]</span>`+
      `<div class="ch">${v.char||"?"} · 元素${v.element}</div>`+
      `<div class="sa">SA: ${v.sa_title}</div>`+
      (v.attack?`<div class="st">攻 ${v.attack} · 血 ${v.life}</div>`:"")+
      (q?`<div class="q">${q}</div>`:"");
    d.title=v.sa_desc?v.sa_title+"\\n"+v.sa_desc:v.sa_title;
    g.appendChild(d);
  }
}
render();
</script>
</body>
</html>
"""


def export_html(agg: dict, sc: dict, title: str = "我的") -> str:
    """收藏 -> 单文件炫耀页 HTML (零外部请求, 发给谁都能直接打开)。"""
    import datetime
    data = json.dumps(agg, ensure_ascii=False).replace("</", "<\\/")
    t3 = agg["by_tier"].get("3", 0)
    t2 = agg["by_tier"].get("2", 0)
    t1 = agg["by_tier"].get("1", 0)
    t0 = agg["by_tier"].get("0", 0)
    html = (_HTML_TMPL
            .replace("__TITLE__", title)
            .replace("__GRADE__", sc["grade"])
            .replace("__GRADE_TITLE__", sc["grade_title"])
            .replace("__TOTAL__", str(sc["total"]))
            .replace("__DATE__", datetime.date.today().isoformat())
            .replace("__TOTAL_CARDS__", str(agg["total_cards"]))
            .replace("__KINDS__", str(agg["variant_kinds"]))
            .replace("__CHARS__", str(sc["chars_covered"]))
            .replace("__C__", str(sc["completeness"]))
            .replace("__R__", str(sc["rarity"]))
            .replace("__V__", str(sc["coverage"]))
            .replace("__T3__", str(t3)).replace("__T2__", str(t2))
            .replace("__T1__", str(t1)).replace("__T0__", str(t0))
            .replace("__DATA__", data))
    return html
