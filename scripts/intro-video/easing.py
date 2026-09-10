# -*- coding: utf-8 -*-
"""緩動與路徑取樣。純函式，無 I/O，因此補間曲線可以獨立驗證。"""
import math
from typing import Tuple

Point = Tuple[float, float]


def ease_in_out_cubic(t):
    if t <= 0.0:
        return 0.0
    if t >= 1.0:
        return 1.0
    if t < 0.5:
        return 4.0 * t * t * t
    return 1.0 - pow(-2.0 * t + 2.0, 3) / 2.0


def _segment_lengths(points):
    return [math.hypot(b[0] - a[0], b[1] - a[1])
            for a, b in zip(points, points[1:])]


def sample_path(points, n_frames):
    """沿折線依「總弧長進度」取樣，進度走 ease-in-out。

    用弧長而非分段平均，往返路徑（拖過頭再拖回）才不會在轉折處變速。
    """
    if n_frames <= 0:
        raise ValueError("n_frames 必須為正整數")
    points = [(float(p[0]), float(p[1])) for p in points]
    if len(points) < 2:
        raise ValueError("路徑至少需要兩個關鍵點")
    if n_frames == 1:
        return [points[0]]

    lengths = _segment_lengths(points)
    total = sum(lengths)
    if total == 0.0:
        return [points[0]] * n_frames

    out = []
    for i in range(n_frames):
        target = ease_in_out_cubic(i / float(n_frames - 1)) * total
        acc = 0.0
        for idx, seg in enumerate(lengths):
            if acc + seg >= target or idx == len(lengths) - 1:
                local = 0.0 if seg == 0.0 else (target - acc) / seg
                local = min(max(local, 0.0), 1.0)
                a, b = points[idx], points[idx + 1]
                out.append((a[0] + (b[0] - a[0]) * local,
                            a[1] + (b[1] - a[1]) * local))
                break
            acc += seg
    return out
