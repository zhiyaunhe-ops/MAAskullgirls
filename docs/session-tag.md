# 场次条件口径：按tag 归类，不搞父子

> 2026-10-06 用户口径。README 只讲「这是什么/怎么跑」，实现口径与踩坑留这里。

## 为什么改

原先场次靠**父子继承**取条件：`arena_rules.json` 的 `parent_id` →
`ScoreStore.create_child()` 继承 `score_target`／`energy_cost`／`rule`。
问题：

- 条件挂在「某个场次」上，父子一断就无处取值；
- 类别靠**读场地名猜**（`arena_rules.json` 的 `kind` 是 B/C 级，表内注释自认）。

改为**按 tag 归类**：条件从一张纯表取，tag 来自 **sgmnow 官方分类**。

## 条件表

定义在 `tools/pf_artag.py` 的 `TAG_CONDITIONS`（唯一权威）。

| tag | score_target | energy_cost | rule |
|---|---|---|---|
| `character` | 5000万 | 5 | `{"type":"class","value":<当期角色>}`，**仅约束防守队** |
| `element` / `rift` | 5000万 | 4 | `{"type":"element","value":<对应元素>}` |
| `monthly_character` | **None（无上限）** | 4 | None |
| `monthly_element` | **None（无上限）** | 4 | `{"type":"element","value":<对应元素>}` |
| `gold` / `assist` / `move` / 未知 | 4000万 | 4 | None |

**「无上限」= `score_target: null`，不是漏填。** `pf_bot.py:571` 的判据是
`if score_target is not None and val >= target` ⇒ None 直接跳过上界判定，
一直打到手动停。A 级依据：两类月场在 `sessions.json` 里 tgt 本就全是 None，
实测总分 188,331,229 / 191,962,012（1.5～1.9 亿量级），给具体上限必然提前截断。

## 每日流程

`pf_schedule.classify_and_create()` 是新建场次的唯一入口，**顺序即约束**：

1. **`JJC.today()` 先更新场次** —— 当天有快照就用，没有现抓 sgmnow。
   缺这一步＝拿昨天数据比今天场地。
2. `pf_artag.classify_arena()` 按名称相似度定 tag（阈值 **0.75**）。
3. 条件**一律从 `TAG_CONDITIONS` 按 tag 取**，不继承任何场次。
4. `ScoreStore.create_tagged()` 建独立场次（名字带日期，供连刷编排识别）。

### 降级行为（都不猜、不崩）

| 情况 | 行为 |
|---|---|
| 快照 stale（当日未取到，显示历史归档） | 显式告警，不当今日实况 |
| 名称不过阈值 / 判不出类别 | **照样建场次**，取最保守 4kw/4能量/无规则 + warn |
| 元素/角色类但没产出 rule | 告警（缺防守角色/元素名＝约束丢失） |
| `JJC` 抓取失败 | 仍能建场次（条件退最保守） |

「不因认不出类别就不建场次」—— 否则用户会**无场次可跑**。

## tag 判定依据

优先级从高到低，迁移记录与 `tag_basis` 字段逐条写明，可事后追溯：

1. **`monthly_manual.json` 人工补丁**（月场专用，见下）
2. **现有 `rule` 字段** —— 非 null 的 rule 是实测/人工确认过的硬约束
3. **场次名里的类别词** —— `角色周场`/`金币场`/`202609元素月场` 等自起名（A 级）
4. `energy_cost=5` 推断为角色场 —— ⚠️ **这是推断不是依据**

## 月场必须人工补

sgmnow 的 Monthly PF **只有一个 `holi` 条目、只给场次名** —— 既不区分
「角色月场／元素月场」，也不给元素名（实测 14 天快照 `holi` 恒为
`{name, active, scope}`，无类别字段）。月场每月才换一次名，轮换规律也推不出来。

⇒ 该信息在 **`tools/data/monthly_manual.json`** 人工维护：

| 字段 | 作用 |
|---|---|
| `tag` | `monthly_character` / `monthly_element` |
| `element` | 元素月场的元素名（角色月场为 `null`） |
| `valid_from` / `valid_until` | 生效区间，**过期即失效**，不静默沿用 |
| `match` | 本地自起名列表（一对多）；纯英文官方名可省略 |
| `依据` | 判定依据，逐条留痕 |

**每月 1 日加新条目 + 给上一条填 `valid_until`。**
⚠️ **别把旧条目的 `valid_until` 改成 `null`**（＝永不过期，一年后没人说得清）。

## 两个必须知道的坑

### ⚠️ 中文名归一后不可区分

`pf_artag._key()` 只保留 `[A-Z0-9]`，中文被滤光：

```
'202609元素月场' → '202609'
'202609角色月场' → '202609'      ← 两者不可区分
```

早先拿 `_name` 建人工表索引 ⇒ **角色月场被判成元素月场并挂上 wind 规则**。
修法：人工表用显式 `match` 列表；`_manual_match()` 两段式 —— 先按**原文**
精确/子串比（中文名唯一可靠通道），**原文含非 ASCII 时禁止走归一层**。

### ⚠️ 两张规则表的键格式相反，不能一刀切归一

| 表 | 键格式 | rule 值 |
|---|---|---|
| `CHARACTER_CHIPS`（`pf_bot.py:112-119`） | **带空格的官方原名**（`Cerebella`／`Ms. Fortune`） | **原样保留** |
| `ELEMENT_CHIPS`（`pf_bot.py:104`） | **小写**（`fire`） | 必须归一 |

`get()` 返回 None 时 `pf_bot` 只打一句 warn 就跳过 ⇒ **约束静默失效**。
class 值绑一个游戏里不存在的角色比绑 null 更糟：防守队永远填不进该角色、
CONFIRM 点不动、场次打不完。

## 维护操作

```bash
# 手动触发一次新场流程（先更新 sgmnow → 扫 hub → 定tag → 建场次 → 起 bot）
python tools/pf_schedule.py --action run_new_pf

# 只看会归成什么 tag（不建场次、不起 bot）
python tools/pf_schedule.py --action run_new_pf --params '{"dry_run":true}'

# 存量场次迁移到 tag 口径（幂等，每次先自动备份）
python tools/migrate_sessions_to_tag.py --dry-run   # 先看逐条before/after
python tools/migrate_sessions_to_tag.py --apply
```

⚠️ **`run_new_pf` / `run_pf` 会先停掉正在跑的 bot**（`restart` 默认开，新场次
要独占模拟器）。队列里正在跑的那场会被出队、不再自动接续；分数已落盘不丢，
之后 `/api/start` 续跑即可。迁移脚本同样会在 bot 运行时拒绝执行（除非 `--force`）。

⚠️ **改 `debug/pf/sessions.json` 前必须先停 bot** ——
`ScoreStore.record()` 每次采样都 `sess["score"]=...` 后 `_save_sessions()`
**重写整个文件**（`pf_storage.py:277-280`）⇒ bot 在跑时任何外部写入都会被
下一场战斗**静默覆盖**。同理**跑全量测试前先停bot**（`tests/test_respawn_exit.py`
的 mtime 断言会因bot 正常写入而失败 —— 那不是回归，是环境）。

## 退役的东西

| 退役项 | 位置 | 说明 |
|---|---|---|
| `parent_id` 继承链 | `arena_rules.json` + `ScoreStore.create_child()` | 保留函数但不用于新建 |
| `resolve_arena()` | `pf_schedule.py` | 保留但只服务旧 `parent_session` 任务 |
| `parents` / `default_parent` / `auto_parent` | `act_run_new_pf` params | 已无意义 |

`parent` 字段在 `sessions.json` 里**保留作历史留档**（存量 26 个），不清空——
不可逆，且要保留「当时挂在哪类下」的信息。

## 代码入口

| 用途 | 位置 |
|---|---|
| 条件表 + 归类 | `tools/pf_artag.py` |
| 月场人工表 | `tools/data/monthly_manual.json` |
| 新建场次入口 | `pf_schedule.classify_and_create()` |
| 带 tag 建场次 | `ScoreStore.create_tagged()` |
| 存量迁移 | `tools/migrate_sessions_to_tag.py` |
| 单测 | `tests/test_artag.py`、`tests/test_schedule_tag.py` |
