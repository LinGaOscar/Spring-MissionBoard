# -*- coding: utf-8 -*-
"""把分鏡與 raw 幀展開成 30fps 圖片序列。純離線——調節奏只要重跑這支。"""
import json
import os
import sys

from PIL import Image

import storyboard as sb
from compositing import (crop_sprite, crossfade, draw_boxes, draw_caption,
                         draw_cursor, ken_burns, paste_sprite)
from easing import ease_in_out_cubic, sample_path


def required_frame_ids(scenes):
    ids = []
    for scene in scenes:
        if scene.drag is None:
            ids.append(scene.id)
        else:
            ids.append(scene.drag.base_id)
            ids.append(scene.drag.sprite_from)
    return ids


def _frame_count(scene):
    return max(1, int(round(scene.seconds * sb.FPS)))


def _caption_alpha(i, n):
    k = sb.CAPTION_FADE_FRAMES
    if n <= 2 * k:
        return 1.0
    if i < k:
        return (i + 1) / float(k + 1)
    if i >= n - k:
        return (n - i) / float(k + 1)
    return 1.0


def _load_raw(scenes, raw_dir):
    raw = {}
    for name in set(required_frame_ids(scenes)):
        path = os.path.join(raw_dir, name + ".png")
        if not os.path.exists(path):
            raise FileNotFoundError("缺少擷取幀 {}，請先跑 capture.py".format(path))
        img = Image.open(path).convert("RGB")
        # 奇數高度過不了 yuv420p，寧可在這裡炸掉也不要在編碼階段才發現
        if img.width % 2 or img.height % 2:
            raise ValueError(
                "{} 尺寸 {}x{} 含奇數邊，無法編成 yuv420p".format(
                    name, img.width, img.height))
        # 偶數不代表尺寸正確：擷取階段若因 DPI／viewport 設定跑偏，
        # 可能一致地產出「偶數但不是 1600x812」的幀，crossfade 也擋不住
        # （相鄰幀尺寸一致就不會報錯），必須在這裡精確比對才不會靜默出錯片。
        if img.width != sb.WIDTH or img.height != sb.HEIGHT:
            raise ValueError(
                "{} 尺寸 {}x{} 與規格 {}x{} 不符".format(
                    name, img.width, img.height, sb.WIDTH, sb.HEIGHT))
        raw[name] = img
    return raw


def _scene_frames(scene, raw, meta):
    n = _frame_count(scene)
    frames = []
    if scene.drag is None:
        base = raw[scene.id]
        zoom_a, zoom_b = scene.zoom
        for i in range(n):
            progress = 0.0 if n == 1 else i / float(n - 1)
            scale = zoom_a + (zoom_b - zoom_a) * ease_in_out_cubic(progress)
            img = ken_burns(base, scale)
            img = draw_boxes(img, scene.boxes)
            frames.append(draw_caption(img, scene.caption, _caption_alpha(i, n)))
        return frames

    base = raw[scene.drag.base_id]
    bbox = meta["sprites"][scene.drag.sprite_from]
    sprite = crop_sprite(raw[scene.drag.sprite_from], bbox)
    for i, pos in enumerate(sample_path(scene.drag.path, n)):
        img = paste_sprite(base, sprite, pos, rotate_deg=scene.drag.rotate_deg)
        img = draw_cursor(img, pos)
        img = draw_boxes(img, scene.boxes)
        frames.append(draw_caption(img, scene.caption, _caption_alpha(i, n)))
    return frames


def build_sequence(scenes, raw_dir, meta, out_dir):
    raw = _load_raw(scenes, raw_dir)
    if os.path.isdir(out_dir):
        for stale in os.listdir(out_dir):
            os.remove(os.path.join(out_dir, stale))
    else:
        os.makedirs(out_dir)

    index = 0
    previous_last = None
    for scene in scenes:
        frames = _scene_frames(scene, raw, meta)
        # 轉場就地做在下一場景的開頭，總幀數才等於各場景秒數加總
        if previous_last is not None:
            k = min(sb.TRANSITION_FRAMES, len(frames))
            for j in range(k):
                frames[j] = crossfade(previous_last, frames[j],
                                      (j + 1) / float(k + 1))
        for frame in frames:
            frame.save(os.path.join(out_dir, "%05d.png" % index))
            index += 1
        previous_last = frames[-1]
    return index


def main():
    if len(sys.argv) != 3:
        raise SystemExit("用法: python3 sequencer.py <raw_dir> <out_dir>")
    raw_dir, out_dir = sys.argv[1], sys.argv[2]
    with open(os.path.join(raw_dir, "meta.json")) as handle:
        meta = json.load(handle)
    total = build_sequence(sb.SCENES, raw_dir, meta, out_dir)
    print("寫出 {} 幀（{:.1f} 秒 @ {}fps）".format(
        total, total / float(sb.FPS), sb.FPS))


if __name__ == "__main__":
    main()
