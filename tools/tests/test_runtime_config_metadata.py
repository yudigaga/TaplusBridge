import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.analyze_lsp_archive import analyze


TAG = "[io.github.yudigaga.taplusbridge,TaplusBridge,I] "
LOG = "log/modules_2026-11-06T08:00:00.000000.log"


class RuntimeConfigMetadataTest(unittest.TestCase):
    def archive(self, root, module="", full=""):
        path = root / "LSPosed_20261106_082242.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(LOG, module)
            archive.writestr("full.log", full)
        return path

    def test_publication_and_runtime_read_retain_only_fixed_metadata(self):
        private = "identity=PRIVATE_ID url=https://private.example/message text=SECRET_BODY nonce=PRIVATE_NONCE"
        module = (
            f"2026-11-06T08:00:00.000 {TAG}runtime config stage=stored build=63 supplemental_tap=off {private}\n"
            "[ 2026-11-06T08:00:01.000     1000:  3182:  3182 I/LSPosedFramework ] "
            "(system)[io.github.yudigaga.taplusbridge,TaplusBridge,c6e-build,0,1] "
            f"runtime config stage=loaded build=63 supplemental_tap=off {private}\n"
        )
        full = f"11-06 08:00:02.000 u0_a123 123 456 I TaplusBridge: runtime config stage=confirmed build=63 supplemental_tap=on {private}\n"
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full))
        events = result.get("runtime_config_events", [])
        self.assertEqual(["stored", "loaded", "confirmed"], [event["stage"] for event in events])
        self.assertEqual(["off", "off", "on"], [event["supplemental_tap"] for event in events])
        self.assertEqual([63, 63, 63], [event["build"] for event in events])
        self.assertEqual(["confirmed", "loaded", "stored"], result.get("observed_runtime_config_stages", []))
        for event in events:
            self.assertEqual({"source", "line", "at", "stage", "build", "supplemental_tap"}, set(event))
        self.assertEqual([(LOG, 1), (LOG, 2), ("full.log", 1)], [(event["source"], event["line"]) for event in events])
        encoded = json.dumps(result)
        for secret in ("PRIVATE_ID", "private.example", "SECRET_BODY", "PRIVATE_NONCE", '"identity"', '"nonce"', '"raw"'):
            self.assertNotIn(secret, encoded)

    def test_invalid_fields_and_quoted_or_other_logger_messages_are_ignored(self):
        messages = (
            "runtime config stage=stored_private build=63 supplemental_tap=off",
            "runtime config stage=stored-private build=63 supplemental_tap=off",
            "runtime config stage=stored build=-1 supplemental_tap=off",
            "runtime config stage=stored build=0 supplemental_tap=off",
            "runtime config stage=stored build=2147483648 supplemental_tap=off",
            "runtime config stage=stored build=63private supplemental_tap=off",
            "runtime config stage=stored build=63 supplemental_tap=false",
            "runtime config stage=stored build=63 supplemental_tap=off-private",
            "runtime config stage=stored supplemental_tap=off",
            "quoted runtime config stage=loaded build=63 supplemental_tap=off",
        )
        module = "\n".join(f"2026-11-06T08:00:00.000 {TAG}{message}" for message in messages)
        module += (
            "\n2026-11-06T08:00:01.000 [other,Other,I] runtime config stage=stored build=63 supplemental_tap=off\n"
            f"2026-11-06T08:00:02.000 [other,Other,I] quoted {TAG}runtime config stage=loaded build=63 supplemental_tap=off\n"
            f"2026-11-06T08:00:03.000 {TAG}quoted {TAG}runtime config stage=loaded build=63 supplemental_tap=off\n"
        )
        full = (
            "11-06 08:00:00.000 I OtherTag: TaplusBridge: runtime config stage=stored build=63 supplemental_tap=off\n"
            "11-06 08:00:00.000 I TaplusBridgeOther: runtime config stage=stored build=63 supplemental_tap=off\n"
            "11-06 08:00:00.000 I TaplusBridge: quoted runtime config stage=stored build=63 supplemental_tap=off\n"
            "11-06 08:00:00.000 I TaplusBridge: runtime config stage=loaded build=63 supplemental_tap=off-private\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full))
        self.assertEqual([], result.get("runtime_config_events"))
        self.assertEqual([], result.get("observed_runtime_config_stages"))

    def test_config_events_follow_the_selected_date_and_after_clock(self):
        module = (
            f"2026-11-05T08:00:00.000 {TAG}runtime config stage=stored build=62 supplemental_tap=on\n"
            f"2026-11-06T07:59:59.000 {TAG}runtime config stage=stored build=63 supplemental_tap=off\n"
            f"2026-11-06T08:00:00.000 {TAG}runtime config stage=loaded build=63 supplemental_tap=off\n"
        )
        full = (
            "11-05 08:00:00.000 I TaplusBridge: runtime config stage=confirmed build=62 supplemental_tap=on\n"
            "11-06 07:59:59.000 I TaplusBridge: runtime config stage=stored build=63 supplemental_tap=off\n"
            "11-06 08:00:01.000 I TaplusBridge: runtime config stage=confirmed build=63 supplemental_tap=off\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), module, full)
            result = analyze(path, after="08:00:00")
            previous = analyze(path, date="2026-11-05", after="08:00:00")
        self.assertEqual("2026-11-06", result["selected_date"])
        self.assertEqual(["loaded", "confirmed"], [event["stage"] for event in result.get("runtime_config_events", [])])
        self.assertEqual(["stored", "confirmed"], [event["stage"] for event in previous.get("runtime_config_events", [])])
        self.assertEqual([62, 62], [event["build"] for event in previous.get("runtime_config_events", [])])

    def test_unrelated_capture_events_keep_their_existing_schema(self):
        module = f"2026-11-06T08:00:00.000 {TAG}assist capture stage=empty\n"
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module))
        self.assertEqual(["empty"], result["observed_assist_capture_stages"])
        self.assertEqual([], result.get("runtime_config_events"))
        self.assertEqual([], result.get("observed_runtime_config_stages"))


if __name__ == "__main__":
    unittest.main()
