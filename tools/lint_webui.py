#!/usr/bin/env python3
"""Validate the standalone WebUI without importing bot or emulator modules."""

import re
from html.parser import HTMLParser
from pathlib import Path
import subprocess
import sys
import tempfile


class Assets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.references = []
        self.ids = set()
        self.errors = []
        self.inline = []
        self.in_script = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        ident = attrs.get("id")
        if ident in self.ids:
            self.errors.append(f"duplicate HTML id: {ident}")
        if ident:
            self.ids.add(ident)
        if tag == "script":
            self.in_script = not attrs.get("src")
            if attrs.get("src"):
                self.references.append(attrs["src"])
        if tag == "link" and attrs.get("rel") == "stylesheet":
            self.references.append(attrs.get("href", ""))
        for name, code in attrs.items():
            if name.startswith("on") and code:
                self.inline.append(f"function handler() {{ {code} }}")

    def handle_endtag(self, tag):
        if tag == "script":
            self.in_script = False

    def handle_data(self, data):
        if self.in_script:
            self.inline.append(data)


TOP_DECL = re.compile(r"^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)|^(?:let|const|var)\s+([A-Za-z_$][\w$]*)")


def duplicate_top_level(script: Path) -> list:
    """顶层函数/变量声明重名检查 (2026-10-02)。

    JS 函数声明同名时**后者覆盖前者**且不报错 —— 连刷编排的 renderPool 被
    每日任务页签同名函数静默覆盖, node --check 与浏览器控制台全绿, 但面板
    永远空 (用户实测)。这里对每个 script 扫顶层声明, 重名即报错。
    """
    seen, dups = {}, []
    for no, line in enumerate(script.read_text(encoding="utf-8").splitlines(), 1):
        m = TOP_DECL.match(line)
        if not m:
            continue
        name = m.group(1) or m.group(2)
        if name in seen:
            dups.append(f"duplicate top-level declaration {name!r} in {script.name} "
                        f"(line {seen[name]} and {no}) —— 后者会静默覆盖前者")
        else:
            seen[name] = no
    return dups


def main():
    root = Path(__file__).resolve().parent.parent
    static = root / "tools" / "static"
    parser = Assets()
    parser.feed((static / "webui.html").read_text(encoding="utf-8"))
    errors = parser.errors
    for reference in parser.references:
        if not reference.startswith("/static/"):
            errors.append(f"unexpected external asset: {reference}")
            continue
        target = static / reference.removeprefix("/static/")
        if not target.is_file():
            errors.append(f"missing referenced asset: {reference}")
    scripts = sorted(static.glob("*.js"))
    for script in scripts:
        errors.extend(duplicate_top_level(script))
    with tempfile.TemporaryDirectory(prefix="sgm-webui-lint-") as tmp:
        inline = Path(tmp) / "inline.js"
        inline.write_text("\n".join(parser.inline), encoding="utf-8")
        for script in [*scripts, inline]:
            result = subprocess.run(
                ["node", "--check", str(script)], capture_output=True, text=True
            )
            if result.returncode:
                errors.append(f"invalid JavaScript in {script.name}:\n{result.stderr.strip()}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"WebUI lint passed: HTML references, unique ids, {len(scripts)} scripts and inline handlers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
