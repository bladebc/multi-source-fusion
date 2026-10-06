import tempfile
import unittest
from pathlib import Path
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pdr
from loader import load_sensors


class PdrRegressionTests(unittest.TestCase):
    def load(self, times, duplicate=False):
        with tempfile.TemporaryDirectory() as td:
            for name in ('accelerometer', 'gyroscope', 'magnetometer'):
                stamps = (np.asarray(times) * 1e9).astype(np.int64) + 1_000_000_000
                if duplicate:
                    stamps[1] = stamps[0]
                pd.DataFrame(dict(timestamp_elapsed_ns=stamps, x=0., y=0.,
                                  z=9.80665 if name == 'accelerometer' else 1., accuracy=3)).to_csv(
                    Path(td) / (name + '.csv'), index=False)
            return load_sensors(td)

    def test_short_recording_has_clear_error(self):
        with self.assertRaisesRegex(ValueError, '不足 1 秒'):
            self.load(np.arange(0, .4, .02))

    def test_outage_is_not_interpolated(self):
        with self.assertRaisesRegex(ValueError, '断流'):
            self.load(np.r_[np.arange(0, 1, .02), np.arange(6, 7, .02)])

    def test_duplicate_timestamp_rejected(self):
        with self.assertRaisesRegex(ValueError, '重复时间戳'):
            self.load(np.arange(0, 2, .02), duplicate=True)

    def test_static_recording_has_no_edge_steps(self):
        data = self.load(np.arange(0, 3, .02))
        self.assertLess(np.max(data['lin_norm']), 1e-10)
        self.assertEqual(pdr.detect_steps(data['lin_norm'], data['fs']), [])

    def test_smoothing_preserves_short_length(self):
        np.testing.assert_allclose(pdr.smooth(np.ones(3), 50), np.ones(3))

    def test_invalid_calibration(self):
        for peaks, distance in (([], 10), ([(1, 1)], 10), ([(2, 1)], 0), ([(2, 1)], float('nan'))):
            with self.assertRaises(ValueError):
                pdr.calibrate_K(peaks, distance)

    def test_heading_wrap_and_gyro_sign(self):
        self.assertAlmostEqual(pdr.heading_update(179, -100, -179, .02, .5), -179)
        self.assertAlmostEqual(pdr.heading_update(0, 90, None, 1), -90)

    def test_error_uses_time_not_step_ratio(self):
        # Missing an early step must not align the remaining point to an earlier truth point.
        end, mean = pdr.trajectory_error([0, 3, 4], [0, 0, 0], [3, 4],
                                        [0, 1, 2, 3, 4], [0, 1, 2, 3, 4], [0]*5)
        self.assertEqual((end, mean), (0., 0.))

    def test_trimmed_trajectory_start_uses_trim_time(self):
        # A trajectory beginning at 6 s must not compare its origin to truth at 0 s.
        self.assertEqual(pdr.trajectory_error([0, 1], [0, 0], [7],
                                             [0, 6, 7], [100, 0, 1], [0, 0, 0],
                                             start_time=6), (0., 0.))

    def test_error_excludes_times_outside_truth(self):
        self.assertEqual(pdr.trajectory_error([0, 1, 2], [0, 0, 0], [1, 2],
                                             [1, 2], [1, 2], [0, 0]), (0., 0.))


if __name__ == '__main__':
    unittest.main()
