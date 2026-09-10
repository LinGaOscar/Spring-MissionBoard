import unittest

from PIL import Image

import storyboard as sb
from compositing import (crop_sprite, crossfade, draw_boxes, draw_caption,
                         draw_cursor, ken_burns, load_font, paste_sprite)


def _canvas(color=(200, 200, 200)):
    return Image.new("RGB", (sb.WIDTH, sb.HEIGHT), color)


class TestFont(unittest.TestCase):
    def test_loads_pinned_font(self):
        self.assertIsNotNone(load_font(30))

    def test_missing_font_fails_loudly(self):
        # 靜默 fallback 會產出豆腐字，等到成片才發現代價太高
        with self.assertRaises(FileNotFoundError):
            load_font(30, "/nonexistent/NoSuch.ttc")


class TestCrossfade(unittest.TestCase):
    def setUp(self):
        self.a = _canvas((0, 0, 0))
        self.b = _canvas((255, 255, 255))

    def test_t_zero_is_first_image(self):
        self.assertEqual(crossfade(self.a, self.b, 0.0).getpixel((10, 10)),
                         (0, 0, 0))

    def test_t_one_is_second_image(self):
        self.assertEqual(crossfade(self.a, self.b, 1.0).getpixel((10, 10)),
                         (255, 255, 255))

    def test_midpoint_is_blended(self):
        px = crossfade(self.a, self.b, 0.5).getpixel((10, 10))
        self.assertTrue(all(100 < v < 155 for v in px), px)

    def test_size_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            crossfade(self.a, Image.new("RGB", (10, 10)), 0.5)


class TestCaption(unittest.TestCase):
    def test_alpha_zero_leaves_image_untouched(self):
        base = _canvas()
        self.assertEqual(list(draw_caption(base, "測試字幕", 0.0).getdata()),
                         list(base.convert("RGB").getdata()))

    def test_none_text_leaves_image_untouched(self):
        base = _canvas()
        self.assertEqual(list(draw_caption(base, None, 1.0).getdata()),
                         list(base.convert("RGB").getdata()))

    def test_caption_band_changes_bottom_pixels(self):
        out = draw_caption(_canvas(), "拖曳卡片跨欄改狀態", 1.0)
        self.assertNotEqual(out.getpixel((800, sb.HEIGHT - 40)), (200, 200, 200))

    def test_caption_does_not_touch_top(self):
        out = draw_caption(_canvas(), "拖曳卡片跨欄改狀態", 1.0)
        self.assertEqual(out.getpixel((800, 40)), (200, 200, 200))

    def test_returns_rgb(self):
        self.assertEqual(draw_caption(_canvas(), "字幕", 1.0).mode, "RGB")


class TestSprite(unittest.TestCase):
    def test_paste_preserves_canvas_size(self):
        sprite = crop_sprite(_canvas((10, 20, 30)), (0, 0, 400, 80))
        out = paste_sprite(_canvas(), sprite, (800.0, 400.0))
        self.assertEqual(out.size, (sb.WIDTH, sb.HEIGHT))
        self.assertEqual(out.mode, "RGB")

    def test_paste_changes_pixels_at_target(self):
        sprite = crop_sprite(_canvas((10, 20, 30)), (0, 0, 400, 80))
        out = paste_sprite(_canvas(), sprite, (800.0, 400.0), shadow=False)
        self.assertNotEqual(out.getpixel((800, 400)), (200, 200, 200))

    def test_paste_leaves_far_pixels_alone(self):
        sprite = crop_sprite(_canvas((10, 20, 30)), (0, 0, 400, 80))
        out = paste_sprite(_canvas(), sprite, (800.0, 400.0), shadow=False)
        self.assertEqual(out.getpixel((20, 20)), (200, 200, 200))


class TestBoxesAndCursor(unittest.TestCase):
    def test_box_drawn_in_danger_color(self):
        out = draw_boxes(_canvas(), ((100, 100, 300, 200),))
        self.assertEqual(out.getpixel((200, 100)), sb.DANGER)

    def test_no_boxes_is_noop(self):
        base = _canvas()
        self.assertEqual(list(draw_boxes(base, ()).getdata()),
                         list(base.getdata()))

    def test_cursor_changes_pixels(self):
        out = draw_cursor(_canvas(), (500.0, 500.0))
        self.assertNotEqual(out.getpixel((502, 505)), (200, 200, 200))


class TestKenBurns(unittest.TestCase):
    def test_scale_one_is_noop(self):
        base = _canvas()
        self.assertEqual(list(ken_burns(base, 1.0).getdata()),
                         list(base.getdata()))

    def test_zoom_preserves_size(self):
        self.assertEqual(ken_burns(_canvas(), 1.03).size, (sb.WIDTH, sb.HEIGHT))


if __name__ == "__main__":
    unittest.main()
