import unittest

from comparison_studio.data import AudioTrack
from comparison_studio.soundtrack import build_soundtrack_command


class SoundtrackTests(unittest.TestCase):
    def test_multi_track_command_has_mix_trim_loop_and_fades(self) -> None:
        tracks = [
            AudioTrack(
                path="one.wav",
                start_time=1.5,
                trim_start=2.0,
                trim_end=5.0,
                volume=0.7,
                fade_in=0.5,
                fade_out=0.8,
                loop=True,
            ),
            AudioTrack(path="two.mp3", volume=0.25),
        ]
        command = build_soundtrack_command(
            "ffmpeg", "silent.mp4", "output.mp4", tracks, [10.0, 20.0], 12.0, 0.9
        )
        graph = command[command.index("-filter_complex") + 1]
        self.assertIn("amix=inputs=2", graph)
        self.assertIn("aloop=loop=-1", graph)
        self.assertIn("afade=t=in", graph)
        self.assertIn("adelay=delays=1500", graph)
        self.assertIn("volume=0.900000", graph)
        self.assertEqual(command[-1], "output.mp4")


if __name__ == "__main__":
    unittest.main()
