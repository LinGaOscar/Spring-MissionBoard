import re
import unittest

import storyboard as sb


class TestStoryboard(unittest.TestCase):
    def test_total_length_within_spec(self):
        # spec 綁定條件：成片總長 60-63 秒
        self.assertGreaterEqual(sb.total_seconds(), 60.0)
        self.assertLessEqual(sb.total_seconds(), 63.0)

    def test_total_frames_matches_fps(self):
        self.assertEqual(sb.total_frames(), sum(
            max(1, int(round(s.seconds * sb.FPS))) for s in sb.SCENES))

    def test_scene_ids_unique(self):
        ids = [s.id for s in sb.SCENES]
        self.assertEqual(len(ids), len(set(ids)))

    def test_drag_scene_is_never_a_card(self):
        # 拖曳需要真實截圖當底圖與 sprite 來源，不可能是自繪卡片
        for s in sb.SCENES:
            if s.drag is not None:
                with self.subTest(scene=s.id):
                    self.assertIsNone(s.card_html, "拖曳場景不能同時是自繪卡片")

    def test_captions_present_on_ui_scenes(self):
        # UI 畫面一律要有字幕；片頭/片尾卡自帶文字，不需要字幕列
        for s in sb.SCENES:
            if s.card_html is None:
                with self.subTest(scene=s.id):
                    self.assertTrue(s.caption, "UI 場景必須有字幕")

    def test_drag_paths_have_at_least_two_points(self):
        for s in sb.SCENES:
            if s.drag is not None:
                with self.subTest(scene=s.id):
                    self.assertGreaterEqual(len(s.drag.path), 2)

    def test_dates_are_absolute(self):
        # Global Constraint：禁用相對日期，否則跨日重跑畫面會變
        for key, value in sb.DATES.items():
            with self.subTest(key=key):
                self.assertRegex(value, r"^\d{4}-\d{2}-\d{2}$")

    def test_ken_burns_only_on_cards(self):
        for s in sb.SCENES:
            if s.zoom != (1.0, 1.0):
                with self.subTest(scene=s.id):
                    self.assertIsNotNone(
                        s.card_html, "UI 畫面禁止縮放（14px 文字會閃爍）")


if __name__ == "__main__":
    unittest.main()
