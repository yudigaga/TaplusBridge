import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.analyze_lsp_archive import analyze


TAG = "[io.github.yudigaga.taplusbridge,TaplusBridge,I] "
LOG = "log/modules_2026-11-06T08:00:00.000000.log"


class RecognitionSourceMetadataTest(unittest.TestCase):
    def archive(self, root, module="", full=""):
        path = root / "LSPosed_20261106_082242.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(LOG, module)
            archive.writestr("full.log", full)
        return path

    def test_six_link_sources_retain_fixed_fields_and_log_provenance(self):
        channels = ("native", "assist", "ocr", "server", "clipboard", "probe")
        module = "\n".join(
            f"2026-11-06T08:00:{i:02}.000 {TAG}"
            f"recognition source stage=links channel={channel} found=1 added=1 total=8"
            for i, channel in enumerate(channels))
        module += (
            "\n[ 2026-11-06T08:00:06.000     1000:  3182:  3182 I/LSPosedFramework ] "
            "(system)[io.github.yudigaga.taplusbridge,TaplusBridge,c6e-build,0,1] "
            "recognition source stage=links channel=assist found=0 added=0 total=0\n"
        )
        full = (
            "11-06 08:00:07.000 u0_a123 123 456 I TaplusBridge: "
            "recognition source stage=links channel=probe found=8 added=8 total=8\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full))
        events = result.get("recognition_source_events", [])
        self.assertEqual([*channels, "assist", "probe"], [event["channel"] for event in events])
        self.assertEqual(["assist", "clipboard", "native", "ocr", "probe", "server"],
                         result.get("observed_recognition_sources"))
        for event in events:
            self.assertEqual({"source", "line", "at", "stage", "channel", "found", "added", "total"}, set(event))
            self.assertEqual("links", event["stage"])
        self.assertEqual([(LOG, 1), (LOG, 7), ("full.log", 1)],
                         [(events[i]["source"], events[i]["line"]) for i in (0, 6, 7)])
        self.assertEqual(["2026-11-06T08:00:00.000", "2026-11-06T08:00:06.000", "11-06 08:00:07.000"],
                         [events[i]["at"] for i in (0, 6, 7)])
        self.assertEqual([(0, 0, 0), (8, 8, 8)],
                         [(event["found"], event["added"], event["total"]) for event in events[-2:]])

    def test_duplicate_links_keep_the_observed_source_even_when_nothing_is_added(self):
        module = (
            f"2026-11-06T08:00:00.000 {TAG}recognition source stage=links channel=native found=1 added=1 total=1\n"
            f"2026-11-06T08:00:01.000 {TAG}recognition source stage=links channel=assist found=1 added=0 total=1\n"
            f"2026-11-06T08:00:02.000 {TAG}recognition source stage=links channel=assist found=1 added=0 total=1\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module))
        events = result.get("recognition_source_events", [])
        self.assertEqual([("native", 1, 1, 1), ("assist", 1, 0, 1), ("assist", 1, 0, 1)],
                         [(event["channel"], event["found"], event["added"], event["total"]) for event in events])
        self.assertEqual(["assist", "native"], result.get("observed_recognition_sources"))

    def test_unknown_stages_sources_and_malformed_or_injected_fields_are_ignored(self):
        valid = "recognition source stage=links channel=native found=1 added=1 total=1"
        invalid = (
            valid.replace("stage=links", "stage=unknown"),
            valid.replace("stage=links", "stage=links-private"),
            valid.replace("stage=links", "stage=links_private"),
            valid.replace("channel=native", "channel=segment"),
            valid.replace("channel=native", "channel=unknown"),
            valid.replace("channel=native", "channel=native-private"),
            valid.replace("channel=native", "channel=NATIVE"),
            valid.replace("channel=native ", ""),
            valid.replace("found=1 ", ""),
            valid.replace("added=1 ", ""),
            valid.replace(" total=1", ""),
            valid + " channel=probe",
            valid + " found=8",
            valid + " stage=links",
            valid + " url=https://private.example/message text=SECRET_BODY nonce=PRIVATE_NONCE",
            valid.replace("found=1 added=1", "added=1 found=1"),
        )
        invalid += tuple(valid.replace(f"{key}=1", f"{key}={value}")
                         for key in ("found", "added", "total")
                         for value in ("-1", "+1", "9", "88", "01", "1private", "1.0", "１"))
        module = "\n".join(f"2026-11-06T08:00:00.000 {TAG}{message}" for message in invalid)
        full = "\n".join(f"11-06 08:00:00.000 I TaplusBridge: {message}" for message in invalid)
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full))
        self.assertEqual([], result.get("recognition_source_events"))
        self.assertEqual([], result.get("observed_recognition_sources"))
        encoded = json.dumps(result)
        for secret in ("private.example", "SECRET_BODY", "PRIVATE_NONCE", '"nonce"', '"raw"'):
            self.assertNotIn(secret, encoded)

    def test_quoted_messages_and_other_loggers_cannot_forge_source_events(self):
        message = "recognition source stage=links channel=native found=1 added=1 total=1"
        module = (
            f"2026-11-06T08:00:00.000 [other,Other,I] {message}\n"
            f"2026-11-06T08:00:00.000 [other,Other,I] quoted {TAG}{message}\n"
            f"2026-11-06T08:00:00.000 {TAG}quoted {TAG}{message}\n"
            f"2026-11-06T08:00:00.000 {TAG}quoted {message}\n"
            "[ 2026-11-06T08:00:00.000 1000: 3182: 3182 I/OtherLogger ] "
            f"(system){TAG}{message}\n"
            f"{TAG}{message}\n"
        )
        full = (
            f"11-06 08:00:00.000 I OtherTag: TaplusBridge: {message}\n"
            f"11-06 08:00:00.000 I TaplusBridgeOther: {message}\n"
            f"11-06 08:00:00.000 I TaplusBridge.Other: {message}\n"
            f"11-06 08:00:00.000 I TaplusBridge: quoted {message}\n"
            f"TaplusBridge: {message}\n"
            f"11-06 08:00:00.000 I OtherTag: {TAG}{message}\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module, full))
        self.assertEqual([], result.get("recognition_source_events"))
        self.assertEqual([], result.get("observed_recognition_sources"))

    def test_source_events_follow_the_selected_date_and_after_clock(self):
        module = (
            f"2026-11-05T08:00:00.000 {TAG}recognition source stage=links channel=clipboard found=1 added=1 total=1\n"
            f"2026-11-06T07:59:59.000 {TAG}recognition source stage=links channel=native found=1 added=1 total=1\n"
            f"2026-11-06T08:00:00.000 {TAG}recognition source stage=links channel=assist found=1 added=0 total=1\n"
        )
        full = (
            "11-05 08:00:00.000 I TaplusBridge: recognition source stage=links channel=server found=1 added=1 total=1\n"
            "11-06 07:59:59.000 I TaplusBridge: recognition source stage=links channel=ocr found=1 added=1 total=1\n"
            "11-06 08:00:01.000 I TaplusBridge: recognition source stage=links channel=probe found=1 added=0 total=1\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), module, full)
            result = analyze(path, after="08:00:00")
            previous = analyze(path, date="2026-11-05", after="08:00:00")
        self.assertEqual("2026-11-06", result["selected_date"])
        self.assertEqual(["assist", "probe"], result.get("observed_recognition_sources"))
        self.assertEqual(["assist", "probe"], [event["channel"] for event in result.get("recognition_source_events", [])])
        self.assertEqual(["clipboard", "server"], previous.get("observed_recognition_sources"))
        self.assertEqual(["clipboard", "server"], [event["channel"] for event in previous.get("recognition_source_events", [])])

    def test_capture_and_segmentation_diagnostics_do_not_infer_link_sources(self):
        module = (
            f"2026-11-06T08:00:00.000 {TAG}assist capture stage=received\n"
            f"2026-11-06T08:00:01.000 {TAG}recognition stage=capture_segment input=10 total=1 outcome=captured\n"
            f"2026-11-06T08:00:02.000 {TAG}recognition stage=capture_ocr input=10 total=1 outcome=captured\n"
            f"2026-11-06T08:00:03.000 {TAG}probe stage=blocked outcome=URL_FOUND\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp), module))
        self.assertEqual(["received"], result["observed_assist_capture_stages"])
        self.assertEqual(["capture_ocr", "capture_segment"], result["observed_stages"])
        self.assertEqual([], result.get("recognition_source_events"))
        self.assertEqual([], result.get("observed_recognition_sources"))


if __name__ == "__main__":
    unittest.main()
