"""pf 系列模块静态卫生检查 (2026-10-02)。

起因: pf_nav.py 从 pf_bot.py 拆出时漏搬 TPL_HUB_PLAY, py_compile 通过
(名字在调用期才解析), 实机一点「扫描」就 NameError 带崩 run() 主循环,
服务整个退出 (bot_stdout_1002-130010.log)。symtable 能在 import 前把
"函数体内引用的全局名" 全枚举出来 —— 本测试对每个 pf 模块断言:
所有被引用的全局名都在本模块定义/导入过 (builtins 与 dunder 除外)。

跑法: 裸 python 即可 (纯 ast/symtable, 不 import 目标模块):
    python -m pytest tests/test_module_hygiene.py -q
"""
import builtins
import symtable
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent
MODULES = ["tools/pf_nav.py", "tools/pf_bot.py", "tools/pf_webui.py",
           "tools/pf_scene.py", "tools/pf_storage.py", "tools/preview_webui.py"]

BUILTINS = set(dir(builtins))


def undefined_globals(src: str, filename: str) -> set:
    """函数体内以全局语义引用、但模块未定义/未导入的名字集合。"""
    st = symtable.symtable(src, filename, "exec")
    defined = {s.get_name() for s in st.get_symbols()
               if s.is_assigned() or s.is_imported()}

    missing = set()

    def walk(tbl):
        for child in tbl.get_children():
            for s in child.get_symbols():
                name = s.get_name()
                if (s.is_global() and name not in defined
                        and name not in BUILTINS
                        and not name.startswith("__")):
                    missing.add(name)
            walk(child)

    walk(st)
    return missing


@pytest.mark.parametrize("rel", MODULES)
def test_no_undefined_global_names(rel):
    src = (REPO / rel).read_text(encoding="utf-8")
    missing = undefined_globals(src, rel)
    assert not missing, (f"{rel} 引用了未定义的全局名 {sorted(missing)} —— "
                         f"py_compile 查不出这种错 (调用期才解析), 会在实机触发时炸")


# ---------- WebUI 前端重名闸门 (2026-10-02) ----------
# 起因: 连刷编排的 renderPool 与每日任务页签同名, 后者静默覆盖前者 →
# 「今日场地」面板永远空, node --check/浏览器控制台全绿。见 tests/webui_chain_render.mjs。

def _lint_webui():
    import sys
    if str(REPO / "tools") not in sys.path:
        sys.path.insert(0, str(REPO / "tools"))
    import lint_webui
    return lint_webui


def test_no_duplicate_top_level_declarations():
    lw = _lint_webui()
    dups = []
    for js in sorted((REPO / "tools" / "static").glob("*.js")):
        dups.extend(lw.duplicate_top_level(js))
    assert not dups, dups


def test_duplicate_check_detects_synthetic(tmp_path):
    lw = _lint_webui()
    f = tmp_path / "dupes.js"
    f.write_text("function a() {}\nfunction a() {}\nlet b = 1;\nfunction b() {}\n",
                 encoding="utf-8")
    dups = lw.duplicate_top_level(f)
    assert len(dups) == 2 and any("'a'" in d for d in dups) and any("'b'" in d for d in dups), dups
