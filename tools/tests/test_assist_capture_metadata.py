import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.analyze_lsp_archive import analyze


TAG = "[io.github.yudigaga.taplusbridge,TaplusBridge,I] "
LOG = "log/modules_2026-11-05T16:00:00.000000.log"


class AssistCaptureMetadataTest(unittest.TestCase):
    def archive(self, root, module="", full=""):
        path = root / "LSPosed_20261105_160000.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(LOG, module)
            archive.writestr("full.log", full)
        return path

    def test_actual_assist_stages_are_reported_without_source_payload(self):
        stages = ("requested", "received", "empty", "unavailable", "timeout", "identity_changed",
                  "window_changed", "reader_busy")
        private = "url=https://private.example/message text=SECRET_BODY x=1234 y=5678 nonce=PRIVATE_NONCE"
        module = "\n".join(f"2026-11-05T16:00:{i:02}.000 {TAG}assist capture stage={stage} {private}"
                           for i, stage in enumerate(stages))
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module))
        events = result.get("assist_capture_events", [])
        self.assertEqual(list(stages), [event["stage"] for event in events])
        self.assertEqual(sorted(stages), result.get("observed_assist_capture_stages", []))
        self.assertEqual(set(("source", "line", "at", "stage")), set(events[0]))
        self.assertEqual([], result["capture_events"])
        self.assertEqual([], result["scope_capture_events"])
        encoded = json.dumps(result)
        for secret in ("private.example", "SECRET_BODY", "PRIVATE_NONCE", '"x"', '"y"', '"nonce"'):
            self.assertNotIn(secret, encoded)

    def test_unknown_stages_and_quoted_or_other_logger_messages_are_ignored(self):
        module = (
            f"2026-11-05T16:00:00.000 {TAG}assist capture stage=requested_private\n"
            f"2026-11-05T16:00:01.000 {TAG}assist capture stage=requested-private\n"
            f"2026-11-05T16:00:02.000 {TAG}assist capture stage=gesture_changed\n"
            f"2026-11-05T16:00:03.000 {TAG}text contains assist capture stage=requested\n"
            "2026-11-05T16:00:04.000 [other,Other,I] assist capture stage=received\n"
            f"2026-11-05T16:00:05.000 {TAG}assist capture stage=armed\n"
            f"2026-11-05T16:00:06.000 {TAG}text contains assist capture stage=armed\n"
        )
        full = (
            "11-05 16:00:00.000 I OtherTag: TaplusBridge: assist capture stage=requested\n"
            "11-05 16:00:01.000 I TaplusBridgeOther: assist capture stage=requested\n"
            "11-05 16:00:02.000 I TaplusBridge: text contains assist capture stage=requested\n"
            "11-05 16:00:03.000 I TaplusBridge: assist capture stage=requested-private\n"
            "11-05 16:00:04.000 I TaplusBridge: assist capture stage=PRIVATE_STAGE\n"
            "11-05 16:00:05.000 I TaplusBridge: assist capture stage=received url=https://private.example/\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full))
        self.assertEqual([("full.log", 6, "received")],
                         [(event["source"], event["line"], event["stage"])
                          for event in result.get("assist_capture_events", [])])
        self.assertNotIn("PRIVATE_", json.dumps(result))
        self.assertEqual([], result["capture_events"])

    def test_assist_events_follow_the_selected_date_and_after_clock(self):
        module = (
            f"2026-11-04T16:00:00.000 {TAG}assist capture stage=empty\n"
            f"2026-11-05T15:59:59.000 {TAG}assist capture stage=requested\n"
            f"2026-11-05T16:00:02.000 {TAG}assist capture stage=received\n"
        )
        full = (
            "11-04 16:00:03.000 I TaplusBridge: assist capture stage=timeout\n"
            "11-05 15:59:59.000 I TaplusBridge: assist capture stage=requested\n"
            "11-05 16:00:03.000 system 1000 1001 I TaplusBridge: assist capture stage=empty\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full), after="16:00:00")
        self.assertEqual(["received", "empty"], [event["stage"] for event in result.get("assist_capture_events", [])])
        self.assertEqual("2026-11-05", result["selected_date"])

    def test_existing_scope_capture_schema_is_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp),
                f"2026-11-05T16:00:00.000 {TAG}scope capture stage=submitted reason=none\n"))
        self.assertEqual(["submitted"], result["observed_scope_capture_stages"])
        self.assertEqual("none", result["scope_capture_events"][0]["reason"])
        self.assertIn("assist_capture_events", result)
        self.assertEqual([], result["assist_capture_events"])


if __name__ == "__main__":
    unittest.main()
