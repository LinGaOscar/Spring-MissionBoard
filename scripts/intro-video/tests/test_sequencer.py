import json
import os
import shutil
import tempfile
import unittest

from PIL import Image

import storyboard as sb
from sequencer import build_sequence, required_frame_ids


def _scene(**kw):
    base = dict(id="x", seconds=1.0, caption=None, boxes=(), drag=None,
                zoom=(1.0, 1.0), card_html=None)
    base.update(kw)
    return sb.Scene(**base)


class TestRequiredFrameIds(unittest.TestCase):
    def test_static_scene_needs_own_id(self):
        self.assertEqual(required_frame_ids([_scene(id="a")]), ["a"])

    def test_drag_scene_needs_base_and_sprite_source(self):
        scene = _scene(id="d", drag=sb.Drag("d_base", "d_clean",
                                            ((0.0, 0.0), (10.0, 0.0))))
        self.assertEqual(sorted(required_frame_ids([scene])),
                         ["d_base", "d_clean"])

    def test_real_storyboard_ids_are_unique(self):
        ids = required_frame_ids(sb.SCENES)
        self.assertEqual(len(ids), len(set(ids)))


class TestBuildSequence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.raw = os.path.join(self.tmp, "raw")
        self.out = os.path.join(self.tmp, "seq")
        os.makedirs(self.raw)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def _write_raw(self, name, color):
        Image.new("RGB", (sb.WIDTH, sb.HEIGHT), color).save(
            os.path.join(self.raw, name + ".png"))

    def test_frame_count_matches_seconds(self):
        self._write_raw("a", (10, 10, 10))
        n = build_sequence([_scene(id="a", seconds=2.0)], self.raw, {}, self.out)
        self.assertEqual(n, 60)
        self.assertEqual(len(os.listdir(self.out)), 60)

    def test_frames_are_zero_padded_and_contiguous(self):
        self._write_raw("a", (10, 10, 10))
        build_sequence([_scene(id="a", seconds=0.5)], self.raw, {}, self.out)
        names = sorted(os.listdir(self.out))
        self.assertEqual(names[0], "00000.png")
        self.assertEqual(names[-1], "%05d.png" % (len(names) - 1))

    def test_output_dimensions_are_even(self):
        self._write_raw("a", (10, 10, 10))
        build_sequence([_scene(id="a", seconds=0.2)], self.raw, {}, self.out)
        img = Image.open(os.path.join(self.out, "00000.png"))
        self.assertEqual(img.width % 2, 0)
        self.assertEqual(img.height % 2, 0)

    def test_odd_sized_raw_frame_rejected(self):
        Image.new("RGB", (1600, 811), (0, 0, 0)).save(
            os.path.join(self.raw, "odd.png"))
        with self.assertRaises(ValueError):
            build_sequence([_scene(id="odd", seconds=0.2)], self.raw, {},
                           self.out)

    def test_wrong_but_even_sized_raw_frame_rejected(self):
        # 偶數不代表尺寸正確：擷取階段若整批跑偏（DPI／viewport），
        # 產出的幀可能兩邊都是偶數卻不是 1600x812，crossfade 也擋不住這種錯
        Image.new("RGB", (1598, 810), (0, 0, 0)).save(
            os.path.join(self.raw, "wrong.png"))
        with self.assertRaises(ValueError):
            build_sequence([_scene(id="wrong", seconds=0.2)], self.raw, {},
                           self.out)

    def test_scene_boundary_is_crossfaded(self):
        # 硬切是舊片的病灶之一；轉場幀必須是兩場景的混合色
        self._write_raw("a", (0, 0, 0))
        self._write_raw("b", (255, 255, 255))
        build_sequence([_scene(id="a", seconds=1.0), _scene(id="b", seconds=1.0)],
                       self.raw, {}, self.out)
        first_of_b = Image.open(os.path.join(self.out, "00030.png"))
        px = first_of_b.getpixel((800, 100))
        self.assertTrue(all(0 < v < 255 for v in px), px)

    def test_drag_scene_moves_sprite_between_frames(self):
        # 流暢度的根本驗證：相鄰幀畫面必須不同（舊片這裡是 0.5 秒完全靜止）
        self._write_raw("d_clean", (30, 30, 30))
        self._write_raw("d_base", (30, 30, 30))
        scene = _scene(id="d", seconds=1.0,
                       drag=sb.Drag("d_base", "d_clean",
                                    ((400.0, 300.0), (1200.0, 300.0))))
        meta = {"sprites": {"d_clean": [200, 260, 600, 340]}}
        build_sequence([scene], self.raw, meta, self.out)
        a = Image.open(os.path.join(self.out, "00014.png")).tobytes()
        b = Image.open(os.path.join(self.out, "00015.png")).tobytes()
        self.assertNotEqual(a, b)

    def test_missing_sprite_meta_fails_loudly(self):
        self._write_raw("d_clean", (30, 30, 30))
        self._write_raw("d_base", (30, 30, 30))
        scene = _scene(id="d", seconds=0.5,
                       drag=sb.Drag("d_base", "d_clean",
                                    ((0.0, 0.0), (10.0, 0.0))))
        with self.assertRaises(KeyError):
            build_sequence([scene], self.raw, {"sprites": {}}, self.out)


if __name__ == "__main__":
    unittest.main()
