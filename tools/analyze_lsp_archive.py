"""Summarize TaplusBridge evidence from an LSPosed archive; omit messages, URLs and nonces."""
import argparse
import hashlib
import json
import re
import zipfile
from datetime import date as calendar_date
from pathlib import Path


MODULE_TAG = "[io.github.yudigaga.taplusbridge,TaplusBridge,"
MODULE_LOG = re.compile(r"log/modules_\d{4}-\d{2}-\d{2}T[0-9:.]+\.log\Z")
TIMESTAMP = re.compile(r"\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?")
MODULE_EVENTS = (
    "API 102 module loaded", "ContentCatcher session source tracking installed",
    "Host-only local recognition and destination card hooks installed", "Recommendation builder hook installed",
    "Clipboard URL builder hook installed", "Host 4.1.3/40013 contract active",
    "System framework callback reached", "System one-shot observation hook installed",
    "System observation command channel ready", "System observation requires Android 13 or newer",
    "Invisible probe public Binder hook installed", "Invisible probe command channel ready",
    "runtime channel ready", "runtime launch claimed", "runtime launch submitted",
)
FAILURE_EVENTS = (
    "System observer unavailable", "System command channel unavailable", "Capture arm unavailable",
    "Capture report unavailable", "Observation skipped", "Recommendation hook unavailable",
    "Clipboard URL hook unavailable", "Source tracking unavailable; origin unknown",
    "Bridge unavailable; original intent preserved",
    "Invisible probe unavailable", "Invisible probe channel unavailable",
    "Probe preparation rejected", "Probe input rejected", "Probe identity skipped",
    "Probe options cancellation limited", "Probe metadata unavailable",
    "Frozen runtime unavailable", "runtime launch failed",
    "Recognition hooks unavailable", "Recognition enhancement preserved host flow",
)
EXCEPTIONS = frozenset((
    "RuntimeException", "IllegalArgumentException", "IllegalStateException", "SecurityException",
    "ClassCastException", "BadParcelableException", "NoSuchMethodException", "NoSuchFieldException",
    "ClassNotFoundException", "IllegalAccessException", "InvocationTargetException", "NullPointerException",
    "NameNotFoundException", "RemoteException", "DeadObjectException", "UnsupportedOperationException",
    "Rejected", "Unavailable",
    "ReflectiveOperationException", "Throwable",
))
RECOGNITION_STAGES = frozenset((
    "capture_text", "capture_catcher", "capture_image", "capture_clipboard", "capture_ocr",
    "capture_recommendation", "capture_segment", "publish_server", "publish_local", "bind_ner",
    "bind_recommend", "resolve", "apply_card", "icon", "icon_guard",
))
PERF_STAGES = frozenset((
    "pick_to_url", "resolve", "apply_card", "snapshot_read", "route_plan", "target_preview", "open_preflight",
    "target_state_read", "target_unhide", "target_enable", "target_state_verify",
    "activity_resolve", "activity_start", "thaw_open_total", "thaw_and_start",
))
PERF_OUTCOMES = frozenset((
    "cache_hit", "parsed", "module_missing", "unavailable", "resolved", "no_match",
    "ready", "missing", "accepted", "declined", "already_ready", "requested", "checked",
    "submitted", "failed", "success", "none",
))
RECOGNITION_OUTCOMES = frozenset((
    "reached", "success", "skipped", "unsupported", "empty", "none", "failed", "icon_requested",
    "icon_failed", "icon_displayed", "fields_missing", "captured", "published",
))
RECOGNITION_REASONS = frozenset((
    "disabled", "recognition_disabled", "no_match", "ambiguous", "target_missing", "invalid_request",
    "unsupported_host", "unauthorized", "unknown", "empty_response", "invalid_preview", "exception", "resolved",
))
TARGET_PACKAGES = frozenset((
    "none", "tv.danmaku.bili", "com.example.piliplus", "com.google.android.youtube", "com.coolapk.market",
    "com.sina.weibo", "com.zhihu.android", "com.ss.android.ugc.aweme", "com.xingin.xhs", "com.taobao.taobao",
    "com.tmall.wireless", "com.jingdong.app.mall", "com.baidu.searchbox", "com.zhihu.android.lite",
))
SOURCE_PACKAGES = frozenset(("com.tencent.mm", "com.tencent.mobileqq"))
COMPONENTS = frozenset((
    "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
    "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI",
    "com.tencent.mobileqq.activity.QQBrowserActivity",
))
CAPTURE_OUTCOMES = frozenset(("URL_FOUND", "NO_URL", "UNREADABLE_PAYLOAD", "UNSUPPORTED_COMPONENT"))
CAPTURE_ORIGINS = frozenset(("DATA", "RAW_URL", "URL", "TEXT", "NESTED", "NONE"))
PROBE_STAGES = frozenset(("ready", "input_sent", "failed", "blocked", "late_blocked", "waiting_release",
                          "auto_failed", "physical_state", "physical_read", "recognition_wait", "geometry_failed"))
PROBE_REASONS = frozenset((
    "protocol", "snapshot", "source_changed", "window_unavailable", "window_occluded",
    "input_unavailable", "ready_timeout", "capture_timeout", "busy", "cooldown",
    "cancelled", "replaced", "unsupported_request", "no_unique_url", "unreadable",
    "geometry_unavailable", "touch_active", "touch_unknown", "stale_session", "capture_unavailable",
    "invalid_response", "unavailable", "no_completion",
    "touch_read_failed",
    "source_activity_missing", "card_bounds_missing", "host_window_missing", "card_bounds_outside_screen",
    "display_changed", "window_alpha_invalid", "unsupported_covering_window", "host_window_hidden",
    "host_window_changed", "host_window_moved", "host_window_contract", "card_point_missing",
    "unsupported_display", "card_point_expired", "main_thread_required", "probe_context_changed",
    "probe_closed", "window_restore_failed",
))
PROBE_OUTCOMES = frozenset(("URL_FOUND", "NO_UNIQUE_URL"))
PROBE_SOURCES = {"com.tencent.mm": "com.tencent.mm", "wechat": "com.tencent.mm",
                 "com.tencent.mobileqq": "com.tencent.mobileqq", "qq": "com.tencent.mobileqq"}
ACK_CLASS = "io.github.yudigaga.taplusbridge.hook.HostCaptureAction$Handshake$1"
# A logcat header and exact logger tag are required; another logger quoting this text is ignored.
HOST_LOGCAT_PREFIX = (
    r"^\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?\s+"
    r"(?:(?:\d+|root|system|shell|u\d+_[ai]\d+)\s+){0,3}"
    r"[VDIWEF]\s+TaplusBridge\s*:\s*")
HOST_ACK = re.compile(HOST_LOGCAT_PREFIX + r"capture stage=host_ack outcome=(accepted|failed)(?:\s|$)")
HOST_PROBE_RESULT = re.compile(HOST_LOGCAT_PREFIX + r"probe stage=host_result outcome=(captured)(?:\s|$)")
HOST_PROBE = re.compile(HOST_LOGCAT_PREFIX + r"probe stage=")
SYSTEM_RUNTIME_START = re.compile(HOST_LOGCAT_PREFIX.replace("TaplusBridge", "ActivityTaskManager")
    + r"START\s+u([0-9]{1,4})\s+.*?\bact=io\.github\.yudigaga\.taplusbridge\.RUNTIME_OPEN_V1(?=[\s;}])")
SYSTEM_STARTUP_EVENTS = frozenset(("System framework callback reached", "System one-shot observation hook installed",
    "System observation command channel ready", "Invisible probe public Binder hook installed", "Invisible probe command channel ready"))


def valid_date(value):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise ValueError("date must be YYYY-MM-DD")
    calendar_date.fromisoformat(value)
    return value


def infer_date(path, records):
    dates = sorted({at[:10] for _, _, _, at in records if at})
    if dates:
        return dates[-1], "latest_module_event"
    match = re.search(r"(?:^|_)LSPosed_(\d{4})(\d{2})(\d{2})_", "_" + path.name)
    if match:
        return valid_date("-".join(match.groups())), "archive_filename"
    return None, "unavailable"


def numeric_fields(line, ranges):
    fields = {}
    for key, (minimum, maximum) in ranges.items():
        match = re.search(r"\b" + re.escape(key) + r"=(-?\d+)\b", line)
        if match and minimum <= int(match.group(1)) <= maximum:
            fields[key] = int(match.group(1))
    return fields


def enum_field(line, key, allowed):
    match = re.search(r"\b" + re.escape(key) + r"=([A-Za-z0-9_.]+)\b", line)
    return match.group(1) if match and match.group(1) in allowed else None


def exception_field(line):
    match = re.search(r"\bexception=([A-Za-z_$][A-Za-z0-9_.$]*)", line)
    simple = match.group(1).rsplit(".", 1)[-1].rsplit("$", 1)[-1] if match else None
    return simple if simple in EXCEPTIONS else "OTHER"


def probe_metadata(line, stage):
    fields = {"stage": stage}
    if stage in ("ready", "waiting_release"):
        source = enum_field(line, "source", PROBE_SOURCES)
        if source:
            fields["source_package"] = PROBE_SOURCES[source]
        fields.update(numeric_fields(line, {"user": (0, 10000)}))
    if stage in ("failed", "auto_failed", "physical_state", "physical_read", "recognition_wait", "geometry_failed"):
        fields["reason"] = enum_field(line, "reason", PROBE_REASONS) or "OTHER"
    if stage in ("physical_read", "geometry_failed"):
        fields["exception"] = exception_field(line)
    if stage == "blocked":
        fields["outcome"] = enum_field(line, "outcome", PROBE_OUTCOMES) or "OTHER"
    return fields


def analyze(path, after=None, date=None):
    path = Path(path)
    with zipfile.ZipFile(path) as archive:
        records = []
        for name in sorted(name for name in archive.namelist() if MODULE_LOG.fullmatch(name)):
            for number, line in enumerate(archive.read(name).decode("utf-8", "replace").splitlines(), 1):
                if MODULE_TAG not in line:
                    continue
                timestamp = TIMESTAMP.search(line)
                records.append((name, number, line, timestamp.group() if timestamp else None))
        selected_date, date_source = (valid_date(date), "explicit") if date else infer_date(path, records)
        module_events, diagnostic_events, capture_events, failure_events, probe_events, perf_events = [], [], [], [], [], []
        runtime_launch_events = []
        earlier_system = {}
        for name, number, line, at in records:
            if selected_date and (not at or at[:10] != selected_date):
                if at and at[:10] < selected_date:
                    known_system = next((event for event in SYSTEM_STARTUP_EVENTS if event in line), None)
                    if known_system and (known_system not in earlier_system or at > earlier_system[known_system]["at"]):
                        earlier_system[known_system] = {"source": name, "line": number, "at": at, "event": known_system}
                continue
            if after and (not at or at.split("T", 1)[1][:8] < after):
                continue
            provenance = {"source": name, "line": number, "at": at}
            known = next((event for event in MODULE_EVENTS if event in line), None)
            if known:
                event = {**provenance, "event": known}
                build = re.search(r"\bbuild=([0-9]+\.[0-9]+\.[0-9]+-dev/[0-9]+)\b", line)
                if build:
                    event["build"] = build.group(1)
                system = re.search(r"\bsystem_server=(true|false)\b", line)
                if system:
                    event["system_server"] = system.group(1) == "true"
                process = enum_field(line, "process", {"system", "system_server", "com.miui.contentextension"})
                if process:
                    event["process"] = process
                module_events.append(event)
            perf_area = "recognition" if "recognition perf stage=" in line else (
                "runtime" if "runtime perf stage=" in line else None)
            if perf_area:
                stage = enum_field(line, "stage", PERF_STAGES)
                elapsed = numeric_fields(line, {"elapsed_us": (0, 60_000_000)})
                if stage and elapsed:
                    event = {**provenance, "area": perf_area, "stage": stage, **elapsed}
                    outcome = enum_field(line, "outcome", PERF_OUTCOMES)
                    if outcome:
                        event["outcome"] = outcome
                    perf_events.append(event)
            failed = next((event for event in FAILURE_EVENTS if event in line), None)
            if failed:
                suffix = line.split(failed + ":", 1)[-1].strip()
                error = re.match(r"([A-Za-z_$][A-Za-z0-9_.$]*)", suffix)
                simple = error.group(1).rsplit(".", 1)[-1] if error else None
                failure_events.append({**provenance, "event": failed,
                                       "exception": simple if simple in EXCEPTIONS else "OTHER"})
            if "recognition stage=" in line:
                stage = enum_field(line, "stage", RECOGNITION_STAGES)
                outcome = enum_field(line, "outcome", RECOGNITION_OUTCOMES)
                if stage:
                    fields = numeric_fields(line, {key: (0, 131072) for key in
                                                   ("input", "output", "captured", "server", "local", "total")})
                    if outcome:
                        fields["outcome"] = outcome
                    for key, allowed, output_key in (("reason", RECOGNITION_REASONS, "reason"),
                            ("target", TARGET_PACKAGES, "target_package"), ("exception", EXCEPTIONS, "exception")):
                        if re.search(r"\b" + key + "=", line):
                            fields[output_key] = exception_field(line) if key == "exception" else enum_field(line, key, allowed) or "OTHER"
                    diagnostic_events.append({**provenance, "stage": stage, **fields})
            elif "capture stage=" in line:
                stage = enum_field(line, "stage", {"armed", "observed", "command_rejected"})
                if not stage:
                    continue
                event = {**provenance, "stage": stage}
                if stage == "command_rejected":
                    reason = enum_field(line, "reason", {"missing_ack"})
                    if reason:
                        event["reason"] = reason
                event.update(numeric_fields(line, {"user": (0, 10000), "ttl_ms": (0, 15000),
                    "url_count": (0, 8), "fields": (0, 31), "launch_result": (-2147483648, 2147483647)}))
                for key, allowed in (("source", SOURCE_PACKAGES), ("component", COMPONENTS),
                                     ("outcome", CAPTURE_OUTCOMES), ("origin", CAPTURE_ORIGINS)):
                    value = enum_field(line, key, allowed)
                    if value:
                        event["source_package" if key == "source" else key] = value
                for_result = re.search(r"\bfor_result=(true|false)\b", line)
                if for_result:
                    event["for_result"] = for_result.group(1) == "true"
                capture_events.append(event)
            elif re.search(r"\]\s+probe stage=", line):
                stage = enum_field(line, "stage", PROBE_STAGES)
                if not stage:
                    continue
                probe_events.append({**provenance, **probe_metadata(line, stage)})

        text_events, image_events, component_paths = [], [], set()
        lines = archive.read("full.log").decode("utf-8", "replace").splitlines() if "full.log" in archive.namelist() else []
        relevant_records, current = [], None
        for number, line in enumerate(lines, 1):
            component_paths.update(re.findall(
                r"/(?:product|system|system_ext)/[A-Za-z0-9_./-]{0,220}/ContentCatcher[A-Za-z0-9_.-]*\.apk\b", line))
            stamped = re.match(r"\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?", line)
            in_window = stamped and (not selected_date or line[:5] == selected_date[5:]) and (not after or line[6:14] >= after)
            runtime_start = SYSTEM_RUNTIME_START.match(line) if in_window else None
            if runtime_start:
                event = {"source": "full.log", "line": number, "at": stamped.group(),
                         "event": "runtime_marker_reached_activity_start", "user": int(runtime_start.group(1))}
                result = re.search(r"\bresult(?:\s+code)?\s*[=:]\s*(-?[0-9]{1,4})(?:\s|$)", line)
                if result:
                    event["result_code"] = int(result.group(1))
                runtime_launch_events.append(event)
            host_ack = HOST_ACK.match(line) if in_window else None
            if host_ack:
                capture_events.append({"source": "full.log", "line": number, "at": stamped.group(),
                                       "stage": "host_ack", "outcome": host_ack.group(1)})
            host_probe = HOST_PROBE_RESULT.match(line) if in_window else None
            if host_probe:
                probe_events.append({"source": "full.log", "line": number, "at": stamped.group(),
                                     "stage": "host_result", "outcome": host_probe.group(1)})
            elif in_window and HOST_PROBE.match(line):
                stage = enum_field(line, "stage", PROBE_STAGES)
                if stage:
                    probe_events.append({"source": "full.log", "line": number, "at": stamped.group(),
                                         **probe_metadata(line, stage)})
            if in_window and ACK_CLASS in line and ("Class not found when unmarshalling:" in line
                    or "ClassNotFoundException when unmarshalling:" in line):
                failure_events.append({"source": "full.log", "line": number, "at": stamped.group(),
                    "event": "capture_ack_unparcelable", "callback": "host_capture_ack_callback",
                    "exception": "BadParcelableException" if "BadParcelableException" in line else "ClassNotFoundException"})
            if re.match(r"\d\d-\d\d ", line):
                if current:
                    relevant_records.append(current)
                current = None
                if in_window and "Taplus.TextContentExtensionService" in line:
                    current = [number, line]
            elif current:
                current[1] += "\n" + line
        if current:
            relevant_records.append(current)
        for number, line in relevant_records:
            marker = "onPickText sContent:"
            if marker in line:
                value = line.split(marker, 1)[1]
                text_events.append({"source": "full.log", "line": number, "at": line[:18].strip(),
                                    "chars": len(value), "http_tokens": len(re.findall(r"https?://", value))})
            elif "onPickImage: get bitmap from viewBitmap" in line:
                image_events.append({"source": "full.log", "line": number, "at": line[:18].strip(),
                                     "event": "image_bitmap_received", "other_payload_fields": "not present in supplied log"})
        return {"archive_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "selected_date": selected_date, "date_source": date_source, "after_local_time": after,
            "module_events": module_events, "diagnostic_events": diagnostic_events,
            "earlier_system_startup_events": sorted(earlier_system.values(), key=lambda e: (e["at"], e["event"])),
            "capture_events": capture_events, "failure_events": failure_events,
            "probe_events": probe_events,
            "perf_events": perf_events,
            "runtime_launch_events": runtime_launch_events,
            "observed_stages": sorted({event["stage"] for event in diagnostic_events}),
            "observed_capture_stages": sorted({event["stage"] for event in capture_events}),
            "observed_probe_stages": sorted({event["stage"] for event in probe_events}),
            "system_capture_apks": sorted(component_paths), "text_events": text_events, "image_events": image_events,
            "raw_content_persisted": False, "url_hosts_persisted": False,
            "limits": "Only fixed log events and bounded metadata are retained. An armed event does not prove host acknowledgement. Earlier startup events are context, not proof that their process remained alive. Missing events do not prove missing URL fields or reconstruct a destination."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--after", help="Only include events at or after HH:MM:SS local time")
    parser.add_argument("--date", help="Local YYYY-MM-DD; default is latest module event date or archive filename")
    args = parser.parse_args()
    if args.after and not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]", args.after):
        parser.error("--after must be HH:MM:SS")
    try:
        result = json.dumps(analyze(args.archive, args.after, args.date), indent=2, ensure_ascii=True) + "\n"
    except ValueError as error:
        parser.error(str(error))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result, encoding="utf-8")
    print(result)
