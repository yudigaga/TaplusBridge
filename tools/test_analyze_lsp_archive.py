"""Date filtering and content minimization checks for the actual archive-reader seam."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from analyze_lsp_archive import ACK_CLASS, analyze


TAG = "[io.github.yudigaga.taplusbridge,TaplusBridge,I] "
LOG = "log/modules_2026-10-29T10:25:16.467645.log"


class ArchiveAnalysisTest(unittest.TestCase):
    def archive(self, root, module="", full="", filename="LSPosed_20261029_103303.zip"):
        path = root / filename
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(LOG, module)
            archive.writestr("full.log", full)
        return path

    def test_infers_latest_module_date_and_filters_full_log_to_same_day(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T20:00:00.000 " + TAG + "API 102 module loaded; build=0.2.0-dev/5\n"
                "2026-10-29T10:25:21.816 " + TAG + "API 102 module loaded; build=0.2.1-dev/6; system_server=true; process=system_server\n",
                "10-28 20:00:01.000 I Taplus.TextContentExtensionService: onPickText sContent: old hidden message\n"
                "10-29 10:25:22.000 I Taplus.TextContentExtensionService: onPickText sContent: new hidden message\n")
            result = analyze(path)
            self.assertEqual("2026-10-29", result["selected_date"])
            self.assertEqual("latest_module_event", result["date_source"])
            self.assertEqual(["0.2.1-dev/6"], [e["build"] for e in result["module_events"]])
            self.assertTrue(result["module_events"][0]["system_server"])
            self.assertEqual(1, len(result["text_events"]))
            self.assertNotIn("hidden message", json.dumps(result))

    def test_explicit_date_and_clock_filter_keep_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T09:00:00.000 " + TAG + "System framework callback reached\n"
                "2026-10-28T10:00:00.000 " + TAG + "System one-shot observation hook installed\n"
                "2026-10-29T10:00:00.000 " + TAG + "System observation command channel ready\n")
            result = analyze(path, after="09:30:00", date="2026-10-28")
            self.assertEqual("explicit", result["date_source"])
            self.assertEqual(1, len(result["module_events"]))
            self.assertEqual(2, result["module_events"][0]["line"])
            self.assertEqual(LOG, result["module_events"][0]["source"])

    def test_performance_events_keep_only_fixed_stage_timing_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:25:21.816 " + TAG + "recognition perf stage=pick_to_url elapsed_us=123 outcome=success url=https://private.invalid/a\n"
                "2026-10-29T10:25:22.816 " + TAG + "runtime perf stage=activity_start elapsed_us=456 outcome=submitted target=private.package\n"
                "2026-10-29T10:25:23.816 " + TAG + "runtime perf stage=PRIVATE_STAGE elapsed_us=789 outcome=success\n")
            events = analyze(path)["perf_events"]
            self.assertEqual(["recognition", "runtime"], [event["area"] for event in events])
            self.assertEqual(["pick_to_url", "activity_start"], [event["stage"] for event in events])
            self.assertEqual([123, 456], [event["elapsed_us"] for event in events])
            encoded = json.dumps(events)
            self.assertNotIn("private.invalid", encoded)
            self.assertNotIn("private.package", encoded)
            self.assertNotIn("PRIVATE_STAGE", encoded)

    def test_archive_filename_fallback_and_optional_full_log(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "LSPosed_20261029_103303.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("unrelated.log", "unused")
            result = analyze(path)
            self.assertEqual("2026-10-29", result["selected_date"])
            self.assertEqual("archive_filename", result["date_source"])
            self.assertEqual([], result["capture_events"])

    def test_capture_and_exception_fields_are_fixed_and_no_payload_is_retained(self):
        secret = "https://private.example.org/message?nonce=NEVER-RETAIN-ME"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:32:08.065 " + TAG + "capture stage=armed source=com.tencent.mm user=0 ttl_ms=15000 nonce=NEVER-RETAIN-ME\n"
                "2026-10-29T10:32:10.000 " + TAG + "capture stage=observed component=com.tencent.mm.plugin.webview.ui.tools.WebViewUI outcome=URL_FOUND url_count=1 origin=RAW_URL fields=2 for_result=false launch_result=0 url=" + secret + "\n"
                "2026-10-29T10:32:11.000 " + TAG + "Capture report unavailable: IllegalStateException " + secret + "\n"
                "2026-10-29T10:32:12.000 " + TAG + "Capture arm unavailable: PRIVATE_CUSTOM_EXCEPTION " + secret + "\n",
                "10-29 10:32:07.000 I Taplus.TextContentExtensionService: onPickText sContent: " + secret + " secret body\n")
            result = analyze(path)
            encoded = json.dumps(result)
            for private in ("private.example.org", "NEVER-RETAIN-ME", "PRIVATE_CUSTOM_EXCEPTION", "secret body"):
                self.assertNotIn(private, encoded)
            self.assertEqual(["armed", "observed"], result["observed_capture_stages"])
            self.assertEqual("com.tencent.mm", result["capture_events"][0]["source_package"])
            self.assertFalse(result["capture_events"][1]["for_result"])
            self.assertEqual(["IllegalStateException", "OTHER"], [e["exception"] for e in result["failure_events"]])
            self.assertEqual(1, result["text_events"][0]["http_tokens"])
            self.assertNotIn("hosts", result["text_events"][0])
            self.assertIn("does not prove host acknowledgement", result["limits"])

    def test_unknown_log_fields_and_other_modules_are_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:00.000 [other,Other,I] capture stage=observed component=PRIVATE_COMPONENT\n"
                "2026-10-29T10:00:01.000 " + TAG + "recognition stage=PRIVATE_STAGE outcome=PRIVATE_OUTCOME input=1\n"
                "2026-10-29T10:00:02.000 " + TAG + "capture stage=armed source=PRIVATE_SOURCE user=0 ttl_ms=15000\n"
                "2026-10-29T10:00:03.000 " + TAG + "recognition stage=capture_text outcome=empty input=0 output=0 captured=0\n")
            result = analyze(path)
            self.assertEqual(1, len(result["diagnostic_events"]))
            self.assertEqual(1, len(result["capture_events"]))
            self.assertNotIn("PRIVATE_", json.dumps(result))

    def test_capture_ack_class_not_found_has_fixed_event_without_raw_message(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), full=
                "10-29 10:32:08.001 E Parcel: Class not found when unmarshalling: " + ACK_CLASS + "\n"
                "10-29 10:32:08.002 W Bundle: BadParcelableException: ClassNotFoundException when unmarshalling: " + ACK_CLASS + " private payload\n"
                "10-29 10:32:08.003 E Parcel: Class not found when unmarshalling: other.PRIVATE_CLASS\n")
            failures = analyze(path)["failure_events"]
            self.assertEqual(["capture_ack_unparcelable"] * 2, [e["event"] for e in failures])
            self.assertEqual([1, 2], [e["line"] for e in failures])
            self.assertEqual(["ClassNotFoundException", "BadParcelableException"], [e["exception"] for e in failures])
            self.assertNotIn("private", json.dumps(failures))

    def test_rejects_invalid_explicit_dates(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp))
            for value in ("10-29", "2026-02-30", "2026-1-1"):
                with self.assertRaises(ValueError):
                    analyze(path, date=value)

    def test_system_process_and_missing_ack_rejection_are_fixed_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:25:21.816 " + TAG + "API 102 module loaded; build=0.2.2-dev/7; system_server=true; process=system\n"
                "2026-10-29T10:32:08.065 " + TAG + "capture stage=command_rejected reason=missing_ack nonce=PRIVATE_NONCE url=https://private.invalid/\n")
            result = analyze(path)
            self.assertEqual("system", result["module_events"][0]["process"])
            self.assertEqual("command_rejected", result["capture_events"][0]["stage"])
            self.assertEqual("missing_ack", result["capture_events"][0]["reason"])
            self.assertNotIn("PRIVATE_NONCE", json.dumps(result))
            self.assertNotIn("private.invalid", json.dumps(result))

    def test_host_ack_requires_exact_logcat_tag_and_fixed_outcome(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), full=
                "10-29 10:32:08.001 I TaplusBridge: capture stage=host_ack outcome=accepted nonce=PRIVATE_NONCE\n"
                "10-29 10:32:08.002 1000 2000 2001 W TaplusBridge: capture stage=host_ack outcome=failed url=https://private.invalid/\n"
                "10-29 10:32:08.003 u0_a123 2000 2001 I TaplusBridge: capture stage=host_ack outcome=accepted\n"
                "10-29 10:32:08.004 I OtherTag: TaplusBridge: capture stage=host_ack outcome=accepted\n"
                "10-29 10:32:08.005 I OtherTag: I TaplusBridge: capture stage=host_ack outcome=accepted\n"
                "10-29 10:32:08.006 I TaplusBridgeOther: capture stage=host_ack outcome=accepted\n"
                "10-29 10:32:08.007 I TaplusBridge: text contains capture stage=host_ack outcome=accepted\n"
                "10-29 10:32:08.008 I TaplusBridge: capture stage=host_ack outcome=PRIVATE_OUTCOME\n"
                "10-29 10:32:08.009 I TaplusBridge: capture stage=host_ack outcome=accepted_extra\n")
            result = analyze(path)
            self.assertEqual([1, 2, 3], [e["line"] for e in result["capture_events"]])
            self.assertEqual(["accepted", "failed", "accepted"], [e["outcome"] for e in result["capture_events"]])
            self.assertEqual(["host_ack"], result["observed_capture_stages"])
            for private in ("PRIVATE_", "private.invalid", "OtherTag"):
                self.assertNotIn(private, json.dumps(result))

    def test_invisible_probe_startup_and_fixed_stages_keep_no_payload_fields(self):
        secret = "https://private.invalid/message?nonce=NEVER-RETAIN"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T11:00:00.000 " + TAG + "System framework callback reached; installing invisible probe\n"
                "2026-10-29T11:00:00.001 " + TAG + "Invisible probe public Binder hook installed; source launch consumed\n"
                "2026-10-29T11:00:00.002 " + TAG + "Invisible probe command channel ready; protocol=1\n"
                "2026-10-29T11:00:01.000 " + TAG + "probe stage=ready source=com.tencent.mm user=0 nonce=NEVER-RETAIN x=234 y=456 url=" + secret + "\n"
                "2026-10-29T11:00:01.001 " + TAG + "probe stage=input_sent x=234 y=456\n"
                "2026-10-29T11:00:01.002 " + TAG + "probe stage=blocked outcome=URL_FOUND url=" + secret + "\n"
                "2026-10-29T11:00:01.003 " + TAG + "probe stage=failed reason=window_occluded nonce=NEVER-RETAIN\n"
                "2026-10-29T11:00:01.004 " + TAG + "probe stage=late_blocked component=private.Component\n")
            result = analyze(path)
            self.assertEqual(["System framework callback reached", "Invisible probe public Binder hook installed",
                              "Invisible probe command channel ready"], [e["event"] for e in result["module_events"]])
            self.assertEqual(["ready", "input_sent", "blocked", "failed", "late_blocked"],
                             [e["stage"] for e in result["probe_events"]])
            self.assertEqual("com.tencent.mm", result["probe_events"][0]["source_package"])
            self.assertEqual(0, result["probe_events"][0]["user"])
            self.assertEqual("URL_FOUND", result["probe_events"][2]["outcome"])
            self.assertEqual("window_occluded", result["probe_events"][3]["reason"])
            self.assertEqual([], result["capture_events"])
            encoded = json.dumps(result)
            for private in ("private.invalid", "NEVER-RETAIN", "private.Component", '"x"', '"y"', '"nonce"'):
                self.assertNotIn(private, encoded)

    def test_probe_point_echo_stages_keep_no_coordinates_or_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T11:00:01.000 " + TAG + "probe stage=point_confirmed x=234 y=456 nonce=PRIVATE_NONCE\n"
                "2026-10-29T11:00:01.001 " + TAG + "probe stage=point_mismatch url=https://private.invalid/\n")
            result = analyze(path)
            self.assertEqual(["point_confirmed", "point_mismatch"],
                             [e["stage"] for e in result["probe_events"]])
            encoded = json.dumps(result)
            for private in ('"x"', '"y"', '"nonce"', "PRIVATE_NONCE", "private.invalid"):
                self.assertNotIn(private, encoded)

    def test_probe_failures_use_exception_and_reason_allowlists(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T11:00:00.000 " + TAG + "Probe preparation rejected: SecurityException private payload\n"
                "2026-10-29T11:00:00.001 " + TAG + "Probe input rejected: Rejected x=PRIVATE_COORDINATE\n"
                "2026-10-29T11:00:00.002 " + TAG + "Invisible probe channel unavailable: InvocationTargetException\n"
                "2026-10-29T11:00:00.003 " + TAG + "Probe metadata unavailable: PRIVATE_EXCEPTION\n"
                "2026-10-29T11:00:00.004 " + TAG + "probe stage=failed reason=PRIVATE_REASON\n"
                "2026-10-29T11:00:00.005 " + TAG + "probe stage=blocked outcome=NO_UNIQUE_URL\n"
                "2026-10-29T11:00:00.006 " + TAG + "probe stage=blocked outcome=PRIVATE_OUTCOME\n")
            result = analyze(path)
            self.assertEqual(["SecurityException", "Rejected", "InvocationTargetException", "OTHER"],
                             [e["exception"] for e in result["failure_events"]])
            self.assertEqual("OTHER", result["probe_events"][0]["reason"])
            self.assertEqual("NO_UNIQUE_URL", result["probe_events"][1]["outcome"])
            self.assertEqual("OTHER", result["probe_events"][2]["outcome"])
            self.assertNotIn("PRIVATE_", json.dumps(result))
            self.assertNotIn("private payload", json.dumps(result))

    def test_probe_ready_sources_and_users_are_bounded(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T11:00:00.000 " + TAG + "probe stage=ready source=wechat user=0\n"
                "2026-10-29T11:00:00.001 " + TAG + "probe stage=ready source=qq user=10\n"
                "2026-10-29T11:00:00.002 " + TAG + "probe stage=ready source=PRIVATE_SOURCE user=10001\n"
                "2026-10-29T11:00:00.003 [other,Other,I] probe stage=input_sent\n"
                "2026-10-29T11:00:00.004 " + TAG + "probe stage=PRIVATE_STAGE\n")
            result = analyze(path)
            self.assertEqual(3, len(result["probe_events"]))
            self.assertEqual("com.tencent.mm", result["probe_events"][0]["source_package"])
            self.assertEqual("com.tencent.mobileqq", result["probe_events"][1]["source_package"])
            self.assertEqual(10, result["probe_events"][1]["user"])
            self.assertNotIn("source_package", result["probe_events"][2])
            self.assertNotIn("user", result["probe_events"][2])
            self.assertNotIn("PRIVATE_", json.dumps(result))

    def test_probe_host_result_requires_exact_tag_and_does_not_mix_with_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), full=
                "10-29 11:00:00.001 I TaplusBridge: probe stage=host_result outcome=captured nonce=PRIVATE_NONCE\n"
                "10-29 11:00:00.002 1000 2000 2001 I TaplusBridge: probe stage=host_result outcome=captured url=https://private.invalid/\n"
                "10-29 11:00:00.003 I OtherTag: TaplusBridge: probe stage=host_result outcome=captured\n"
                "10-29 11:00:00.004 I OtherTag: I TaplusBridge: probe stage=host_result outcome=captured\n"
                "10-29 11:00:00.005 I TaplusBridgeOther: probe stage=host_result outcome=captured\n"
                "10-29 11:00:00.006 I TaplusBridge: text contains probe stage=host_result outcome=captured\n"
                "10-29 11:00:00.007 I TaplusBridge: probe stage=host_result outcome=PRIVATE_OUTCOME\n"
                "10-29 11:00:00.008 I TaplusBridge: probe stage=host_result outcome=captured_extra\n"
                "10-29 11:00:00.009 I TaplusBridge: capture stage=host_ack outcome=accepted\n")
            result = analyze(path)
            self.assertEqual([1, 2], [e["line"] for e in result["probe_events"]])
            self.assertEqual(["host_result"], result["observed_probe_stages"])
            self.assertEqual([9], [e["line"] for e in result["capture_events"]])
            self.assertEqual(["host_ack"], result["observed_capture_stages"])
            for private in ("PRIVATE_", "private.invalid", "OtherTag"):
                self.assertNotIn(private, json.dumps(result))

    def test_probe_module_and_host_results_follow_the_selected_date_and_clock(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T10:00:00.000 " + TAG + "probe stage=ready source=com.tencent.mm user=0\n"
                "2026-10-29T09:59:59.000 " + TAG + "probe stage=input_sent\n"
                "2026-10-29T10:00:01.000 " + TAG + "probe stage=blocked outcome=URL_FOUND\n",
                "10-28 10:00:02.000 I TaplusBridge: probe stage=host_result outcome=captured\n"
                "10-29 09:59:59.000 I TaplusBridge: probe stage=host_result outcome=captured\n"
                "10-29 10:00:02.000 I TaplusBridge: probe stage=host_result outcome=captured\n")
            result = analyze(path, after="10:00:00", date="2026-10-29")
            self.assertEqual(["blocked", "host_result"], [e["stage"] for e in result["probe_events"]])
            self.assertEqual([3, 3], [e["line"] for e in result["probe_events"]])
            self.assertEqual([], result["capture_events"])

    def test_current_automatic_failures_and_unknown_touch_are_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-30T07:53:58.853 " + TAG + "probe stage=physical_state reason=touch_unknown; input_disabled_until_reload=true nonce=PRIVATE_NONCE\n"
                "2026-10-30T08:13:00.000 " + TAG + "probe stage=waiting_release source=wechat user=0\n"
                "2026-10-30T08:13:10.000 " + TAG + "probe stage=recognition_wait reason=no_completion\n",
                "10-30 08:13:40.904 I TaplusBridge: probe stage=auto_failed reason=geometry_unavailable url=https://private.invalid/\n"
                "10-30 08:13:41.000 I OtherTag: TaplusBridge: probe stage=auto_failed reason=geometry_unavailable\n"
                "10-30 08:13:42.000 I TaplusBridge: probe stage=PRIVATE_STAGE reason=PRIVATE_REASON\n")
            result = analyze(path)
            self.assertEqual(["physical_state", "waiting_release", "recognition_wait", "auto_failed"],
                             [e["stage"] for e in result["probe_events"]])
            self.assertEqual(["touch_unknown", None, "no_completion", "geometry_unavailable"],
                             [e.get("reason") for e in result["probe_events"]])
            self.assertNotIn("PRIVATE_", json.dumps(result))
            self.assertNotIn("private.invalid", json.dumps(result))

    def test_prior_day_system_startup_is_context_without_polluting_current_events(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T20:28:02.515 " + TAG + "System framework callback reached\n"
                "2026-10-29T20:28:02.522 " + TAG + "Invisible probe public Binder hook installed\n"
                "2026-10-29T20:28:09.531 " + TAG + "Invisible probe command channel ready; protocol=2\n"
                "2026-10-30T08:11:27.652 " + TAG + "API 102 module loaded; build=0.4.0-dev/9; system_server=false; process=com.miui.contentextension\n")
            result = analyze(path)
            self.assertEqual("2026-10-30", result["selected_date"])
            self.assertEqual(1, len(result["module_events"]))
            self.assertEqual(3, len(result["earlier_system_startup_events"]))
            self.assertTrue(all(e["at"].startswith("2026-10-29T") for e in result["earlier_system_startup_events"]))

    def test_routing_decline_reasons_and_known_targets_are_fixed_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-30T08:00:00.000 " + TAG + "recognition stage=resolve outcome=none target=none reason=no_match input=1 output=0 captured=1 url=https://private.invalid/\n"
                "2026-10-30T08:00:01.000 " + TAG + "recognition stage=resolve outcome=success target=tv.danmaku.bili reason=resolved input=1 output=1 captured=1\n"
                "2026-10-30T08:00:02.000 " + TAG + "recognition stage=resolve outcome=none target=PRIVATE_PACKAGE reason=PRIVATE_REASON exception=PRIVATE_EXCEPTION\n"
                "2026-10-30T08:00:03.000 " + TAG + "Recognition enhancement preserved host flow: NoSuchFieldException private message\n"
                "2026-10-30T08:00:04.000 " + TAG + "recognition stage=resolve outcome=none target=none reason=exception exception=java.lang.IllegalStateException private text\n")
            result = analyze(path)
            self.assertEqual(["no_match", "resolved", "OTHER", "exception"], [e["reason"] for e in result["diagnostic_events"]])
            self.assertEqual(["none", "tv.danmaku.bili", "OTHER", "none"], [e["target_package"] for e in result["diagnostic_events"]])
            self.assertEqual("IllegalStateException", result["diagnostic_events"][-1]["exception"])
            self.assertEqual("NoSuchFieldException", result["failure_events"][0]["exception"])
            for secret in ("PRIVATE_", "private message", "private.invalid"):
                self.assertNotIn(secret, json.dumps(result))

    def test_physical_read_exception_uses_only_fixed_categories(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-30T08:00:00.000 " + TAG + "probe stage=physical_read reason=touch_read_failed exception=InvocationTargetException; device_known=true device=PRIVATE_DEVICE\n"
                "2026-10-30T08:00:01.000 " + TAG + "probe stage=physical_read reason=touch_read_failed exception=PRIVATE_EXCEPTION url=https://private.invalid/\n")
            events = analyze(path)["probe_events"]
            self.assertEqual(["InvocationTargetException", "OTHER"], [e["exception"] for e in events])
            self.assertNotIn("PRIVATE_", json.dumps(events)); self.assertNotIn("private.invalid", json.dumps(events))

    def test_geometry_failures_keep_fixed_subreasons_without_original_payloads(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-30T08:00:00.000 " + TAG + "probe stage=geometry_failed reason=unsupported_covering_window exception=IllegalStateException url=https://private.invalid/?secret=x\n",
                "10-30 08:00:01.000 I TaplusBridge: probe stage=geometry_failed reason=PRIVATE_REASON exception=PRIVATE_EXCEPTION\n")
            events = analyze(path)["probe_events"]
            self.assertEqual(["unsupported_covering_window", "OTHER"], [e["reason"] for e in events])
            self.assertEqual(["IllegalStateException", "OTHER"], [e["exception"] for e in events])
            for private in ("PRIVATE_", "private.invalid", "secret"):
                self.assertNotIn(private, json.dumps(events))

    def test_unclaimed_runtime_marker_reports_only_the_system_start_result(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-30T15:00:00.000 " + TAG + "runtime launch claimed; result_callback=false nonce=PRIVATE_NONCE\n",
                "10-30 15:00:01.000 1000 2000 2001 I ActivityTaskManager: START u0 {act=io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1 pkg=android url=https://private.invalid/PRIVATE_PATH} from uid 10123 result code=-91\n"
                "10-30 15:00:02.000 I OtherTag: ActivityTaskManager: START u0 {act=io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1} result=-91\n"
                "10-30 15:00:03.000 I ActivityTaskManager: START u0 {act=io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1_suffix} result=-91\n")
            result = analyze(path)
            self.assertEqual(1, len(result["runtime_launch_events"]))
            self.assertEqual(-91, result["runtime_launch_events"][0]["result_code"])
            self.assertEqual("runtime launch claimed", result["module_events"][0]["event"])
            for private in ("PRIVATE_", "private.invalid", "10123"):
                self.assertNotIn(private, json.dumps(result))

    def test_runtime_marker_diagnostics_obey_the_date_and_time_window(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), "2026-10-30T15:00:00.000 " + TAG + "runtime channel ready\n",
                "10-29 15:00:01.000 I ActivityTaskManager: START u0 {act=io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1} result=-91\n"
                "10-30 14:59:59.000 I ActivityTaskManager: START u0 {act=io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1} result=-91\n"
                "10-30 15:00:01.000 I ActivityTaskManager: START u0 {act=io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1} result=-91\n")
            result = analyze(path, after="15:00:00")
            self.assertEqual([3], [e["line"] for e in result["runtime_launch_events"]])

    def test_source_scope_host_gate_keeps_only_fixed_outcomes_modes_and_release_build(self):
        secret = " package=private.secret.package url=https://private.invalid/SECRET_PATH content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-11-01T12:23:00.000 " + TAG + "API 102 module loaded; build=0.9.0/17\n"
                "2026-11-01T12:23:01.000 " + TAG + "source scope stage=host_gate outcome=allowed mode=BLACKLIST" + secret + "\n"
                "2026-11-01T12:23:02.000 " + TAG + "source scope stage=host_gate outcome=denied mode=WHITELIST" + secret + "\n"
                "2026-11-01T12:23:03.000 " + TAG + "source scope stage=host_gate outcome=unmanaged" + secret + "\n"
                "2026-11-01T12:23:04.000 " + TAG + "source scope stage=host_gate outcome=unavailable mode=PRIVATE_MODE" + secret + "\n"
                "2026-11-01T12:23:05.000 " + TAG + "source scope stage=host_gate outcome=PRIVATE_OUTCOME mode=BLACKLIST\n"
                "2026-11-01T12:23:06.000 " + TAG + "text quotes source scope stage=host_gate outcome=allowed mode=BLACKLIST\n"
                "2026-11-01T12:23:07.000 " + TAG + "source scope stage=host_gate outcome=allowed_extra mode=BLACKLIST\n"
                "2026-11-01T12:23:08.000 " + TAG + "source scope stage=PRIVATE_STAGE outcome=allowed mode=BLACKLIST\n")
            result = analyze(path)
            events = result["source_scope_events"]
            self.assertEqual([2, 3, 4, 5], [e["line"] for e in events])
            self.assertEqual(["allowed", "denied", "unmanaged", "unavailable"], [e["outcome"] for e in events])
            self.assertEqual(["BLACKLIST", "WHITELIST", "UNKNOWN", "UNKNOWN"], [e["mode"] for e in events])
            self.assertTrue(all(e["stage"] == "host_gate" for e in events))
            self.assertEqual("0.9.0/17", result["module_events"][0]["build"])
            for private in ("private.secret.package", "private.invalid", "SECRET_", "PRIVATE_"):
                self.assertNotIn(private, json.dumps(result))

    def test_native_source_gate_requires_exact_tag_bounded_size_and_boolean_without_retaining_packages(self):
        secret = "private.secret.package url=https://private.invalid/SECRET_PATH content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), full=
                "10-29 10:00:01.000 8926 13896 I ContentCatcher_TaplusBlackListHelper:  blackList size: 1 package is " + secret + " isInTaplusWhiteList: true\n"
                "10-29 10:00:02.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 12 package is " + secret + " isInTaplusWhiteList: false\n"
                "10-29 10:00:03.000 I OtherTag: ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is secret isInTaplusWhiteList: true\n"
                "10-29 10:00:04.000 I ContentCatcher_TaplusBlackListHelperOther: blackList size: 1 package is secret isInTaplusWhiteList: true\n"
                "10-29 10:00:05.000 I ContentCatcher_TaplusBlackListHelper: text quotes blackList size: 1 package is secret isInTaplusWhiteList: true\n"
                "10-29 10:00:06.000 I ContentCatcher_TaplusBlackListHelper: blackList size: -1 package is secret isInTaplusWhiteList: true\n"
                "10-29 10:00:07.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 65537 package is secret isInTaplusWhiteList: true\n"
                "10-29 10:00:08.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is secret isInTaplusWhiteList: true_extra\n")
            result = analyze(path)
            events = result["source_scope_events"]
            self.assertEqual([1, 2], [e["line"] for e in events])
            self.assertEqual([1, 12], [e["native_blacklist_size"] for e in events])
            self.assertEqual([True, False], [e["allowed"] for e in events])
            self.assertTrue(all(e["stage"] == "native_gate" for e in events))
            self.assertEqual({"source", "line", "at", "stage", "native_blacklist_size", "allowed", "source_kind"}, set(events[0]))
            for private in ("private.secret.package", "private.invalid", "SECRET_", '"package"', "OtherTag"):
                self.assertNotIn(private, json.dumps(result))

    def test_observation_control_is_a_fixed_event_and_never_counted_as_normal_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), full=
                "10-29 10:00:01.000 E Taplus.TextContentExtensionService: contains observe_control_event return package=private.secret.package url=https://private.invalid/SECRET_PATH\n"
                " SECRET_CONTENT onPickText sContent: https://private.invalid/SECRET_MESSAGE\n"
                "10-29 10:00:02.000 E OtherTag: Taplus.TextContentExtensionService: contains observe_control_event return\n"
                "10-29 10:00:03.000 E TaplusXTextContentExtensionService: contains observe_control_event return\n"
                "10-29 10:00:04.000 E Taplus.TextContentExtensionService: quoted contains observe_control_event return\n"
                "10-29 10:00:05.000 E Taplus.TextContentExtensionService: onPickText sContent: SECRET_CONTENT https://private.invalid/SECRET_MESSAGE\n")
            result = analyze(path)
            events = result["source_scope_events"]
            self.assertEqual([1], [e["line"] for e in events])
            self.assertEqual("observe_control", events[0]["stage"])
            self.assertEqual("observation_control_return", events[0]["event"])
            self.assertEqual([6], [e["line"] for e in result["text_events"]])
            self.assertEqual(1, result["text_events"][0]["http_tokens"])
            for private in ("private.secret.package", "private.invalid", "SECRET_", "OtherTag"):
                self.assertNotIn(private, json.dumps(result))

    def test_source_scope_events_follow_selected_date_and_after_for_module_and_native_records(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T10:00:01.000 " + TAG + "source scope stage=host_gate outcome=allowed mode=BLACKLIST\n"
                "2026-10-29T09:59:59.000 " + TAG + "source scope stage=host_gate outcome=denied mode=WHITELIST\n"
                "2026-10-29T10:00:01.000 " + TAG + "source scope stage=host_gate outcome=allowed mode=BLACKLIST\n",
                "10-28 10:00:01.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 10 package is private.secret isInTaplusWhiteList: false\n"
                "10-29 09:59:59.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 10 package is private.secret isInTaplusWhiteList: false\n"
                "10-29 10:00:02.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is private.secret isInTaplusWhiteList: true\n"
                "10-28 10:00:02.000 E Taplus.TextContentExtensionService: contains observe_control_event return\n"
                "10-29 09:59:59.000 E Taplus.TextContentExtensionService: contains observe_control_event return\n"
                "10-29 10:00:03.000 E Taplus.TextContentExtensionService: contains observe_control_event return\n")
            result = analyze(path, after="10:00:00", date="2026-10-29")
            self.assertEqual(["host_gate", "native_gate", "observe_control"], [e["stage"] for e in result["source_scope_events"]])
            self.assertEqual([3, 3, 6], [e["line"] for e in result["source_scope_events"]])
            self.assertNotIn("private.secret", json.dumps(result))

    def test_host_presentation_retains_only_fixed_results_and_ignores_secret_properties(self):
        secret = " package=private.secret.package url=https://private.invalid/SECRET_PATH properties=SECRET_PROPERTIES content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T10:00:00.000 " + TAG + "source scope stage=host_presentation outcome=passed mode=BLACKLIST" + secret + "\n"
                "2026-10-29T09:59:59.000 " + TAG + "source scope stage=host_presentation outcome=passed mode=BLACKLIST" + secret + "\n"
                "2026-10-29T10:00:01.000 " + TAG + "source scope stage=host_presentation outcome=passed mode=BLACKLIST" + secret + "\n"
                "2026-10-29T10:00:02.000 " + TAG + "source scope stage=host_presentation outcome=property_blocked mode=WHITELIST" + secret + "\n"
                "2026-10-29T10:00:03.000 " + TAG + "source scope stage=host_presentation outcome=scope_blocked mode=UNKNOWN" + secret + "\n"
                "2026-10-29T10:00:04.000 " + TAG + "source scope stage=host_presentation outcome=passed" + secret + "\n"
                "2026-10-29T10:00:05.000 " + TAG + "source scope stage=host_presentation outcome=denied mode=BLACKLIST" + secret + "\n"
                "2026-10-29T10:00:06.000 " + TAG + "source scope stage=host_gate outcome=property_blocked mode=BLACKLIST" + secret + "\n"
                "2026-10-29T10:00:07.000 " + TAG + "source scope stage=host_presentation outcome=PRIVATE_RESULT mode=BLACKLIST" + secret + "\n")
            result = analyze(path, after="10:00:00", date="2026-10-29")
            events = result["source_scope_events"]
            self.assertEqual([3, 4, 5, 6], [e["line"] for e in events])
            self.assertEqual(["passed", "property_blocked", "scope_blocked", "passed"], [e["outcome"] for e in events])
            self.assertEqual(["BLACKLIST", "WHITELIST", "UNKNOWN", "UNKNOWN"], [e["mode"] for e in events])
            self.assertTrue(all(e["stage"] == "host_presentation" for e in events))
            for private in ("private.secret.package", "private.invalid", "SECRET_", "PRIVATE_", '"properties"'):
                self.assertNotIn(private, json.dumps(result))

    def test_source_scope_kind_uses_only_fixed_categories_and_never_retains_raw_packages(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:01.000 " + TAG + "source scope stage=host_gate outcome=allowed mode=BLACKLIST source=lsposed_manager package=private.secret.package\n"
                "2026-10-29T10:00:02.000 " + TAG + "source scope stage=host_gate outcome=allowed mode=BLACKLIST source=root_manager url=https://private.invalid/SECRET\n"
                "2026-10-29T10:00:03.000 " + TAG + "source scope stage=host_presentation outcome=passed mode=WHITELIST source=chrome content=SECRET_CONTENT\n"
                "2026-10-29T10:00:04.000 " + TAG + "source scope stage=host_gate outcome=allowed source=other\n"
                "2026-10-29T10:00:05.000 " + TAG + "source scope stage=host_gate outcome=allowed source=private.secret.package\n"
                "2026-10-29T10:00:06.000 " + TAG + "source scope stage=host_presentation outcome=passed\n"
                "2026-10-29T10:00:07.000 " + TAG + "source scope stage=host_gate outcome=allowed source=chrome_extra\n",
                "10-29 10:00:01.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is org.lsposed.manager isInTaplusWhiteList: true\n"
                "10-29 10:00:02.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is com.resukisu.resukisu isInTaplusWhiteList: true\n"
                "10-29 10:00:03.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is com.android.chrome isInTaplusWhiteList: true\n"
                "10-29 10:00:04.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is private.secret.package isInTaplusWhiteList: true\n"
                "10-29 10:00:05.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is isInTaplusWhiteList: false\n"
                "10-29 10:00:06.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 isInTaplusWhiteList: false\n"
                "10-29 10:00:07.000 I ContentCatcher_TaplusBlackListHelper: blackList size: 1 package is https://private.invalid/SECRET isInTaplusWhiteList: false\n")
            result = analyze(path)
            expected = ["lsposed_manager", "root_manager", "chrome", "other", "unknown", "unknown", "unknown"]
            events = result["source_scope_events"]
            self.assertEqual(expected, [e["source_kind"] for e in events if e["stage"] != "native_gate"])
            self.assertEqual(expected, [e["source_kind"] for e in events if e["stage"] == "native_gate"])
            self.assertTrue(all(e["source"] in {LOG, "full.log"} for e in events))
            for private in ("private.secret.package", "private.invalid", "SECRET", "org.lsposed.manager",
                            "com.resukisu.resukisu", "com.android.chrome", "chrome_extra"):
                self.assertNotIn(private, json.dumps(result))


    def test_scope_capture_system_and_host_stages_keep_only_fixed_metadata(self):
        secret = " source=private.secret.package url=https://private.invalid/SECRET_PATH nonce=SECRET_NONCE path=C:/SECRET_FILE content=SECRET_CONTENT x=123 y=456"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:00.000 " + TAG + "Scope capture system hooks installed" + secret + "\n"
                "2026-10-29T10:00:00.001 " + TAG + "Scope capture host delivery installed" + secret + "\n"
                "2026-10-29T10:00:00.002 " + TAG + "Scope capture system unavailable: NoSuchMethodException" + secret + "\n"
                "2026-10-29T10:00:00.003 " + TAG + "Scope capture host unavailable: IOException" + secret + "\n"
                "2026-10-29T10:00:01.000 " + TAG + "scope capture stage=gesture reason=none" + secret + "\n"
                "2026-10-29T10:00:02.000 " + TAG + "scope capture stage=submitted reason=none" + secret + "\n"
                "2026-10-29T10:00:03.000 " + TAG + "scope capture stage=rejected reason=capture_secure" + secret + "\n"
                "2026-10-29T10:00:04.000 " + TAG + "scope capture stage=failed reason=InvocationTargetException" + secret + "\n"
                "2026-10-29T10:00:05.000 " + TAG + "Scope capture stage=deliver failure=IOException" + secret + "\n",
                "10-29 10:00:06.000 I TaplusBridge: scope capture stage=host_received reason=none" + secret + "\n"
                "10-29 10:00:07.000 I TaplusBridge: scope capture stage=host_submitted reason=none" + secret + "\n")
            result = analyze(path)
            events = result["scope_capture_events"]
            self.assertEqual(["gesture", "submitted", "rejected", "failed", "host_deliver", "host_received", "host_submitted"], [e["stage"] for e in events])
            self.assertEqual(["none", "none", "capture_secure", "InvocationTargetException", "IOException", "none", "none"], [e["reason"] for e in events])
            self.assertTrue(all(set(e) == {"source", "line", "at", "stage", "reason", "source_kind"} for e in events))
            self.assertTrue(all(e["source_kind"] == "unknown" for e in events))
            self.assertEqual(["Scope capture system hooks installed", "Scope capture host delivery installed"], [e["event"] for e in result["module_events"]])
            self.assertEqual(["NoSuchMethodException", "IOException"], [e["exception"] for e in result["failure_events"]])
            self.assertEqual([], result["capture_events"])
            for private in ("private.secret.package", "private.invalid", "SECRET_", '"nonce"', '"x"', '"y"', '"path"'):
                self.assertNotIn(private, json.dumps(result))

    def test_scope_capture_rejects_quoted_loggers_unknown_stages_and_unbounded_reasons(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:01.000 " + TAG + "Scope capture stage=request failure=SecurityException source_kind=chrome\n"
                "2026-10-29T10:00:02.000 " + TAG + "Scope capture stage=read failure=PRIVATE_EXCEPTION source=private.secret.package\n"
                "2026-10-29T10:00:03.000 " + TAG + "Scope capture stage=native failure=IllegalStateException.private.secret\n"
                "2026-10-29T10:00:04.000 " + TAG + "scope capture stage=rejected reason=none:/SECRET_PATH\n"
                "2026-10-29T10:00:05.000 " + TAG + "scope capture stage=PRIVATE_STAGE reason=none\n"
                "2026-10-29T10:00:06.000 " + TAG + "quoted scope capture stage=gesture reason=none\n"
                "2026-10-29T10:00:07.000 [other,Other,I] scope capture stage=gesture reason=none\n",
                "10-29 10:00:01.000 I OtherTag: TaplusBridge: scope capture stage=gesture reason=none\n"
                "10-29 10:00:02.000 I TaplusBridgeOther: scope capture stage=gesture reason=none\n"
                "10-29 10:00:03.000 I TaplusBridge: quoted scope capture stage=gesture reason=none\n"
                "10-29 10:00:04.000 I TaplusBridge: scope capture stage=submitted_extra reason=none\n"
                "10-29 10:00:05.000 I TaplusBridge: Scope capture stage=read failure=NoSuchMethodException source=chrome/private.secret\n")
            result = analyze(path)
            events = result["scope_capture_events"]
            self.assertEqual(["host_request", "host_read", "host_native", "rejected", "host_read"], [e["stage"] for e in events])
            self.assertEqual(["SecurityException", "unknown", "unknown", "unknown", "NoSuchMethodException"], [e["reason"] for e in events])
            self.assertEqual(["chrome", "unknown", "unknown", "unknown", "unknown"], [e["source_kind"] for e in events])
            for private in ("PRIVATE_", "private.secret", "SECRET_PATH", "OtherTag", "submitted_extra"):
                self.assertNotIn(private, json.dumps(result))

    def test_scope_capture_records_obey_date_time_filters_and_fixed_source_categories(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T10:00:01.000 " + TAG + "scope capture stage=gesture reason=none\n"
                "2026-10-29T09:59:59.000 " + TAG + "scope capture stage=submitted reason=none\n"
                "2026-10-29T10:00:01.000 " + TAG + "scope capture stage=gesture reason=none source_kind=lsposed_manager\n"
                "2026-10-29T10:00:02.000 " + TAG + "scope capture stage=submitted reason=none source=root_manager\n",
                "10-28 10:00:01.000 I TaplusBridge: scope capture stage=host_received reason=none\n"
                "10-29 09:59:59.000 I TaplusBridge: scope capture stage=host_received reason=none\n"
                "10-29 10:00:03.000 I TaplusBridge: scope capture stage=host_received reason=none source=chrome\n"
                "10-29 10:00:04.000 I TaplusBridge: scope capture stage=host_submitted reason=none source=com.android.chrome\n")
            result = analyze(path, after="10:00:00", date="2026-10-29")
            events = result["scope_capture_events"]
            self.assertEqual([3, 4, 3, 4], [e["line"] for e in events])
            self.assertEqual(["lsposed_manager", "root_manager", "chrome", "unknown"], [e["source_kind"] for e in events])
            self.assertEqual(["gesture", "host_received", "host_submitted", "submitted"], result["observed_scope_capture_stages"])
            self.assertNotIn("com.android.chrome", json.dumps(result))

    def test_scope_capture_root_cause_retains_only_known_exception_classes_without_messages(self):
        secret = " package=private.secret.package url=https://private.invalid/SECRET_PATH content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:01.000 " + TAG + "Scope capture stage=deliver failure=InvocationTargetException cause=IllegalStateException" + secret + "\n"
                "2026-10-29T10:00:02.000 " + TAG + "Scope capture stage=deliver failure=InvocationTargetException cause=PRIVATE_EXCEPTION" + secret + "\n"
                "2026-10-29T10:00:03.000 " + TAG + "Scope capture stage=deliver failure=InvocationTargetException cause=IllegalStateException:SECRET_MESSAGE" + secret + "\n"
                "2026-10-29T10:00:04.000 " + TAG + "Scope capture stage=deliver failure=InvocationTargetException" + secret + "\n",
                "10-29 10:00:05.000 W TaplusBridge: Scope capture stage=deliver failure=InvocationTargetException cause=NoSuchMethodException" + secret + "\n")
            result = analyze(path); events = result["scope_capture_events"]
            self.assertEqual(["IllegalStateException", "unknown", "unknown", None, "NoSuchMethodException"],
                             [e.get("root_cause") for e in events])
            self.assertTrue(all(e["stage"] == "host_deliver" for e in events))
            self.assertTrue(all(e["reason"] == "InvocationTargetException" for e in events))
            self.assertNotIn("root_cause", events[3])
            for private in ("PRIVATE_", "SECRET_", "private.secret.package", "private.invalid"):
                self.assertNotIn(private, json.dumps(result))

    def test_host_privacy_thread_guard_requires_exact_logger_phrase_and_time_window_without_payloads(self):
        secret = " package=private.secret.package url=https://private.invalid/SECRET_PATH content=SECRET_CONTENT"
        warning = "can not request privacy update in main thread!"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp), full=
                "10-28 10:00:01.000 W Privacy_PrivacyManager: " + warning + secret + "\n"
                "10-29 09:59:59.000 W Privacy_PrivacyManager: " + warning + secret + "\n"
                "10-29 10:00:01.000 I TaplusBridge: scope capture stage=host_received reason=none\n"
                "10-29 10:00:01.001 1000 2000 2001 W Privacy_PrivacyManager: " + warning + secret + "\n"
                "10-29 10:00:01.005 W TaplusBridge: Scope capture stage=deliver failure=InvocationTargetException cause=IllegalStateException\n"
                "10-29 10:00:02.000 W OtherTag: Privacy_PrivacyManager: " + warning + secret + "\n"
                "10-29 10:00:03.000 W Privacy_PrivacyManagerOther: " + warning + secret + "\n"
                "10-29 10:00:04.000 W Privacy_PrivacyManager: quoted " + warning + secret + "\n"
                "10-29 10:00:05.000 W Privacy_PrivacyManager: " + warning + "_extra" + secret + "\n"
                "10-29 10:00:06.000 W Privacy_PrivacyManager: can not request privacy agree in main thread!" + secret + "\n")
            result = analyze(path, after="10:00:00", date="2026-10-29")
            events = result["scope_capture_events"]
            self.assertEqual([3, 4, 5], [e["line"] for e in events])
            self.assertEqual(["host_received", "host_thread_guard", "host_deliver"], [e["stage"] for e in events])
            self.assertEqual("main_thread", events[1]["reason"])
            self.assertEqual("IllegalStateException", events[2]["root_cause"])
            for private in ("SECRET_", "private.secret.package", "private.invalid", "OtherTag"):
                self.assertNotIn(private, json.dumps(result))

    def test_scope_link_fixed_lifecycle_and_installation_events_never_keep_launch_payloads(self):
        secret = " package=private.secret.package url=https://private.invalid/SECRET_PATH nonce=SECRET_NONCE content=SECRET_CONTENT x=123 y=456"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:00.000 " + TAG + "Scope link launch hooks installed" + secret + "\n"
                "2026-10-29T10:00:00.001 " + TAG + "Scope link options cancellation limited: IllegalStateException" + secret + "\n"
                "2026-10-29T10:00:01.000 " + TAG + "scope link stage=armed reason=none" + secret + "\n"
                "2026-10-29T10:00:02.000 " + TAG + "scope link stage=tapped reason=none" + secret + "\n"
                "2026-10-29T10:00:03.000 " + TAG + "scope link stage=blocked reason=http" + secret + "\n"
                "2026-10-29T10:00:04.000 " + TAG + "scope link stage=captured reason=captured" + secret + "\n"
                "2026-10-29T10:00:05.000 " + TAG + "scope link stage=finished reason=timeout" + secret + "\n"
                "2026-10-29T10:00:06.000 " + TAG + "scope link stage=rejected reason=candidate_invalid" + secret + "\n",
                "10-29 10:00:07.000 I TaplusBridge: scope link stage=blocked reason=unsupported_launch" + secret + "\n")
            result = analyze(path); events = result["scope_link_events"]
            self.assertEqual(["armed", "tapped", "blocked", "captured", "finished", "rejected", "blocked"], [e["stage"] for e in events])
            self.assertEqual(["none", "none", "http", "captured", "timeout", "candidate_invalid", "unsupported_launch"], [e["reason"] for e in events])
            self.assertEqual(["armed", "blocked", "captured", "finished", "rejected", "tapped"], result["observed_scope_link_stages"])
            self.assertTrue(all(set(e) == {"source", "line", "at", "stage", "reason", "source_kind"} for e in events))
            self.assertTrue(all(e["source_kind"] == "unknown" for e in events))
            self.assertEqual("Scope link launch hooks installed", result["module_events"][0]["event"])
            self.assertEqual("Scope link options cancellation limited", result["failure_events"][0]["event"])
            self.assertEqual("IllegalStateException", result["failure_events"][0]["exception"])
            for private in ("private.secret.package", "private.invalid", "SECRET_", '"nonce"', '"x"', '"y"'):
                self.assertNotIn(private, json.dumps(result))

    def test_scope_link_rejects_quoted_tags_and_unknown_stages_and_bounds_reason_source_date_and_time(self):
        secret = " url=https://private.invalid/SECRET_PATH nonce=SECRET_NONCE content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-28T10:00:01.000 " + TAG + "scope link stage=armed reason=none\n"
                "2026-10-29T09:59:59.000 " + TAG + "scope link stage=armed reason=none\n"
                "2026-10-29T10:00:01.000 " + TAG + "scope link stage=finished reason=PRIVATE_REASON source=private.secret.package" + secret + "\n"
                "2026-10-29T10:00:02.000 " + TAG + "scope link stage=finished reason=configuration_changed source_kind=chrome" + secret + "\n"
                "2026-10-29T10:00:03.000 " + TAG + "scope link stage=finished reason=none:/SECRET_PATH source=chrome/private.secret\n"
                "2026-10-29T10:00:04.000 " + TAG + "scope link stage=PRIVATE_STAGE reason=none\n"
                "2026-10-29T10:00:05.000 " + TAG + "quoted scope link stage=armed reason=none\n",
                "10-28 10:00:01.000 I TaplusBridge: scope link stage=armed reason=none\n"
                "10-29 09:59:59.000 I TaplusBridge: scope link stage=armed reason=none\n"
                "10-29 10:00:01.000 I OtherTag: TaplusBridge: scope link stage=armed reason=none\n"
                "10-29 10:00:02.000 I TaplusBridgeOther: scope link stage=armed reason=none\n"
                "10-29 10:00:03.000 I TaplusBridge: quoted scope link stage=armed reason=none\n"
                "10-29 10:00:04.000 I TaplusBridge: scope link stage=armed_extra reason=none\n"
                "10-29 10:00:05.000 I TaplusBridge: scope link stage=blocked reason=same_gesture_tail source=root_manager" + secret + "\n")
            result = analyze(path, after="10:00:00", date="2026-10-29"); events = result["scope_link_events"]
            self.assertEqual([3, 4, 5, 7], [e["line"] for e in events])
            self.assertEqual(["unknown", "configuration_changed", "unknown", "same_gesture_tail"], [e["reason"] for e in events])
            self.assertEqual(["unknown", "chrome", "unknown", "root_manager"], [e["source_kind"] for e in events])
            for private in ("PRIVATE_", "SECRET_", "private.secret", "private.invalid", "OtherTag", "armed_extra"):
                self.assertNotIn(private, json.dumps(result))

    def test_scope_link_stage_timing_and_host_handoff_reasons_remain_fixed_metadata(self):
        secret = " url=https://private.invalid/SECRET_PATH nonce=SECRET_NONCE content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            path = self.archive(Path(temp),
                "2026-10-29T10:00:01.000 " + TAG + "scope link stage=finished reason=released_before_recognition" + secret + "\n"
                "2026-10-29T10:00:02.000 " + TAG + "scope link stage=observed reason=native_pick" + secret + "\n",
                "10-29 10:00:03.000 I TaplusBridge: scope link stage=finished reason=host_probe_owner" + secret + "\n"
                "10-29 10:00:04.000 I TaplusBridge: scope link stage=observed reason=native_pick_extra" + secret + "\n")
            result = analyze(path)
            events = result["scope_link_events"]
            self.assertEqual(["finished", "observed", "finished", "observed"], [e["stage"] for e in events])
            self.assertEqual(["released_before_recognition", "native_pick", "host_probe_owner", "unknown"],
                             [e["reason"] for e in events])
            self.assertEqual([1, 2, 1, 2], [e["line"] for e in events])
            self.assertEqual([LOG, LOG, "full.log", "full.log"], [e["source"] for e in events])
            self.assertEqual(["finished", "observed"], result["observed_scope_link_stages"])
            self.assertTrue(all(set(e) == {"source", "line", "at", "stage", "reason", "source_kind"} for e in events))
            for private in ("private.invalid", "SECRET_PATH", "SECRET_NONCE", "SECRET_CONTENT", "native_pick_extra",
                            '"url"', '"nonce"', '"content"'):
                self.assertNotIn(private, json.dumps(result))

    def test_scope_link_launch_rejections_keep_only_fixed_reasons(self):
        reasons = ("unsafe_intent", "web_payload_invalid", "chooser_invalid", "http_data_invalid",
                   "unsupported_action", "unreadable_payload")
        secret = " url=https://private.invalid/SECRET_PATH nonce=SECRET_NONCE content=SECRET_CONTENT"
        with tempfile.TemporaryDirectory() as temp:
            module_log = "".join(
                "2026-10-29T10:00:01.000 " + TAG + "scope link stage=blocked reason=" + reason + secret + "\n"
                for reason in reasons)
            host_log = "".join(
                "10-29 10:00:02.000 I TaplusBridge: scope link stage=blocked reason=" + reason + "_extra" + secret + "\n"
                for reason in reasons)
            result = analyze(self.archive(Path(temp), module_log, host_log))
            events = result["scope_link_events"]
            self.assertEqual(list(reasons) + ["unknown"] * len(reasons), [e["reason"] for e in events])
            self.assertTrue(all(set(e) == {"source", "line", "at", "stage", "reason", "source_kind"} for e in events))
            for private in ("private.invalid", "SECRET_", "_extra", '"url"', '"nonce"', '"content"'):
                self.assertNotIn(private, json.dumps(result))

    def test_drag_handoff_and_haptic_events_keep_only_allowlisted_metadata(self):
        secret = " source=private.package token=SECRET_NONCE x=123 y=456 text=https://private.invalid/SECRET"
        with tempfile.TemporaryDirectory() as temp:
            result = analyze(self.archive(Path(temp),
                "2026-10-29T10:00:01.000 " + TAG
                + "drag gesture stage=takeover_succeeded" + secret + "\n"
                + "2026-10-29T10:00:02.000 " + TAG
                + "drag gesture stage=ready_haptic_requested" + secret + "\n",
                "10-29 10:00:03.000 I TaplusBridge: drag gesture host stage=overlay_requested" + secret + "\n"
                + "10-29 10:00:04.000 I TaplusBridge: drag gesture host stage=overlay_transfer_failed exception=SecurityException"
                + secret + "\n"
                + "10-29 10:00:05.000 I TaplusBridge: drag gesture host stage=links_attached" + secret + "\n"
                + "10-29 10:00:06.000 I TaplusBridge: drag gesture host stage=PRIVATE_STAGE" + secret + "\n"))
            events = result["drag_gesture_events"]
            self.assertEqual(["takeover_succeeded", "ready_haptic_requested", "overlay_requested",
                              "overlay_transfer_failed", "links_attached"], [event["stage"] for event in events])
            self.assertEqual(["unknown", "unknown", "unknown", "SecurityException", "unknown"],
                             [event["reason"] for event in events])
            self.assertEqual(["system", "system", "host", "host", "host"], [event["process"] for event in events])
            self.assertEqual(["links_attached", "overlay_requested", "overlay_transfer_failed",
                              "ready_haptic_requested", "takeover_succeeded"], result["observed_drag_gesture_stages"])
            self.assertTrue(all(set(event) == {"source", "line", "at", "process", "stage", "reason"}
                                for event in events))
            # The archive digest can coincidentally contain a three-digit coordinate.
            metadata = {key: value for key, value in result.items() if key != "archive_sha256"}
            for private in ("private.package", "private.invalid", "SECRET_", "123", "456",
                            "PRIVATE_STAGE", "overlay_transfer_failed exception="):
                self.assertNotIn(private, json.dumps(metadata))


if __name__ == "__main__":
    unittest.main()
