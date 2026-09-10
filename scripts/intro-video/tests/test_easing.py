import math
import unittest

from easing import ease_in_out_cubic, sample_path


def _dist(a, b):
    return math.hypot(b[0] - a[0], b[1] - a[1])


class TestEasing(unittest.TestCase):
    def test_endpoints(self):
        self.assertAlmostEqual(ease_in_out_cubic(0.0), 0.0)
        self.assertAlmostEqual(ease_in_out_cubic(1.0), 1.0)

    def test_midpoint(self):
        self.assertAlmostEqual(ease_in_out_cubic(0.5), 0.5)

    def test_symmetric(self):
        for t in (0.1, 0.25, 0.4):
            self.assertAlmostEqual(
                ease_in_out_cubic(t) + ease_in_out_cubic(1 - t), 1.0)

    def test_clamps_out_of_range(self):
        self.assertEqual(ease_in_out_cubic(-1.0), 0.0)
        self.assertEqual(ease_in_out_cubic(2.0), 1.0)


class TestSamplePath(unittest.TestCase):
    def test_hits_both_endpoints(self):
        pts = sample_path([(0.0, 0.0), (100.0, 0.0)], 10)
        self.assertEqual(len(pts), 10)
        self.assertAlmostEqual(pts[0][0], 0.0)
        self.assertAlmostEqual(pts[-1][0], 100.0)

    def test_speed_ramps_up_then_down(self):
        # 這是「流暢」的核心：位移量必須先增後減，不能等速也不能跳點
        pts = sample_path([(0.0, 0.0), (300.0, 0.0)], 31)
        steps = [_dist(a, b) for a, b in zip(pts, pts[1:])]
        mid = len(steps) // 2
        self.assertGreater(steps[mid], steps[0])
        self.assertGreater(steps[mid], steps[-1])

    def test_no_jump_exceeds_reasonable_step(self):
        # 舊片的病灶：單幀位移 180-220px。補間後任一幀都不該有大跳點
        pts = sample_path([(0.0, 0.0), (440.0, 0.0)], 75)
        steps = [_dist(a, b) for a, b in zip(pts, pts[1:])]
        self.assertLess(max(steps), 20.0)

    def test_round_trip_path_returns_to_target(self):
        # 加映的「拖過頭再拖回」：終點必須落在最後一個關鍵點
        path = [(0.0, 0.0), (100.0, 0.0), (160.0, 10.0), (60.0, 5.0), (100.0, 0.0)]
        pts = sample_path(path, 60)
        self.assertAlmostEqual(pts[-1][0], 100.0, places=3)
        self.assertAlmostEqual(pts[-1][1], 0.0, places=3)

    def test_single_frame_returns_start(self):
        self.assertEqual(sample_path([(5.0, 6.0), (9.0, 9.0)], 1), [(5.0, 6.0)])

    def test_rejects_short_path(self):
        with self.assertRaises(ValueError):
            sample_path([(0.0, 0.0)], 10)

    def test_rejects_non_positive_frames(self):
        with self.assertRaises(ValueError):
            sample_path([(0.0, 0.0), (1.0, 1.0)], 0)


if __name__ == "__main__":
    unittest.main()
