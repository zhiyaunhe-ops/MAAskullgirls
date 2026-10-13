"""离线 OCR 探针: 没有模拟器/设备时也能标定与复验文本 band。

背景 (2026-10-13): `pf_bot.ocr_text` 走 MAA tasker 的 `post_recognition`, 而 tasker
必须 adb 连接成功才 init —— MuMu 不在线时连一个 band 都读不出来, "按注释里的历史
数字改 band" 于是变成常态。本探针用 onnxruntime 直接跑 MAA 资源自带的
`assets/resource/base/model/ocr/{det,rec,keys}`, 复刻 `JOCR(only_rec=True)` 的语义
(整块裁剪直接喂 rec, 不跑 det), 于是纯离线也能读字并做正/负样本复验。

用法:
  # 1) 读若干 ROI
  python tools/_probe_ocr_offline.py read <图> x0,y0,x1,y1 [更多 ROI...]
  # 2) 打分带: 固定 x 范围沿 y 扫 50px 高窗口 (找能稳定读出目标文本的 band)
  python tools/_probe_ocr_offline.py bands <图> x0 x1 y_start y_end
  # 3) 探针扫描: 同一 band 跑一批帧, 报命中帧 (正样本必须有, 负样本不许有)
  python tools/_probe_ocr_offline.py probe <图> x0,y0,x1,y1 PROBE [PROBE...]
         [--glob 'debug/pf/run/**/*.jpg'] [--limit N]

注意: 探针结论只是"这张留档帧读出来是什么", 不等于实机时序 —— 实机路径仍需
用户在场时验证 (见 PF_BOT.md §6.1)。
"""
import argparse
import glob as globmod
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "assets", "resource", "base", "model", "ocr")
REC_H = 48


class RecOCR:
    """MAA rec 模型的最小推理封装 (CPU)。"""

    def __init__(self):
        self.sess = ort.InferenceSession(os.path.join(MODEL_DIR, "rec.onnx"),
                                         providers=["CPUExecutionProvider"])
        self.inp = self.sess.get_inputs()[0].name
        # keys.txt 是 charset (95 字); rec 头比它多 blank(0) 与空格, 越界字符按 '?' 落。
        self.keys = open(os.path.join(MODEL_DIR, "keys.txt"), encoding="utf-8").read().splitlines()

    def _ctc(self, logits):
        idx = logits.argmax(axis=-1)
        out, prev = [], None
        for i in idx:
            if i != prev and i != 0:
                out.append(self.keys[i - 1] if 0 < i <= len(self.keys) else " ")
            prev = i
        return "".join(out).strip()

    def read(self, img_bgr, roi=None):
        if roi is not None:
            x0, y0, x1, y1 = roi
            img_bgr = img_bgr[y0:y1, x0:x1]
        h, w = img_bgr.shape[:2]
        if h == 0 or w == 0:
            return ""
        x = cv2.resize(img_bgr, (max(1, int(round(w * REC_H / h))), REC_H)).astype(np.float32)
        x = (x / 255.0 - 0.5) / 0.5
        return self._ctc(self.sess.run(None, {self.inp: x.transpose(2, 0, 1)[None, ...]})[0][0])


def load(path):
    """cv2.imread 读不了中文路径, 走 np.fromfile。"""
    return cv2.imdecode(np.fromfile(path, dtype=np.uint8), cv2.IMREAD_COLOR)


def norm(s):
    return "".join(ch for ch in s.upper() if ch.isalpha())


def main(argv):
    ap = argparse.ArgumentParser(description="离线 OCR 探针 (无设备标定 band)")
    ap.add_argument("mode", choices=["read", "bands", "probe"])
    ap.add_argument("image")
    ap.add_argument("args", nargs="*", help="ROI(x0,y0,x1,y1) / bands 的 x0 x1 y0 y1 / 探针词")
    ap.add_argument("--glob", dest="glob", default="")
    ap.add_argument("--limit", type=int, default=60)
    a = ap.parse_args(argv)
    ocr = RecOCR()
    img = load(a.image)
    if img is None:
        print(f"读不到图: {a.image}")
        return 2

    if a.mode == "read":
        for spec in a.args:
            roi = tuple(int(v) for v in spec.split(","))
            print(f"  {roi} -> {ocr.read(img, roi)!r}")
        return 0

    if a.mode == "bands":
        x0, x1, y0, y1 = (int(v) for v in a.args)
        for y in range(y0, y1, 10):
            roi = (x0, y, x1, y + 50)
            print(f"  {roi} -> {ocr.read(img, roi)!r}")
        return 0

    roi = tuple(int(v) for v in a.args[0].split(","))
    probes = [norm(p) for p in a.args[1:]]
    frames = [a.image]
    if a.glob:
        frames += sorted(globmod.glob(a.glob, recursive=True))[:a.limit]
    hits, valid = [], 0
    for f in frames:
        im = load(f)
        if im is None or im.shape[0] < 400:
            continue
        valid += 1
        raw = ocr.read(im, roi)
        if any(p in norm(raw) for p in probes):
            hits.append((f, raw))
    print(f"有效帧 {valid}, 命中 {len(hits)}")
    for f, raw in hits:
        print(f"  [HIT] {f} -> {raw!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
