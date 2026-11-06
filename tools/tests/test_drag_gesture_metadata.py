import unittest

from tools.analyze_lsp_archive import drag_gesture_metadata


class DragGestureMetadataTest(unittest.TestCase):
    def test_generic_drag_preparation_stages_retain_only_fixed_metadata(self):
        for stage in ("prepare_requested", "prepare_metadata", "prepare_image", "prepare_image_unavailable",
                      "prepare_capture_unavailable", "prepare_service_unavailable", "prepare_unavailable",
                      "prepared", "prepare_failed", "expired"):
            with self.subTest(stage=stage):
                self.assertEqual(
                    {"stage": stage, "reason": "unknown"},
                    drag_gesture_metadata(f"drag gesture stage={stage} url=https://private.example/secret"),
                )

    def test_system_transfer_rejection_reason_is_preserved(self):
        self.assertEqual(
            {"stage": "takeover_failed", "reason": "input_transfer_rejected"},
            drag_gesture_metadata("drag gesture stage=takeover_failed reason=input_transfer_rejected"),
        )

    def test_host_window_update_reason_is_preserved(self):
        self.assertEqual(
            {"stage": "overlay_transfer_failed", "reason": "host_window_update_failed"},
            drag_gesture_metadata(
                "drag gesture host stage=overlay_transfer_failed reason=host_window_update_failed"
            ),
        )

    def test_overlay_hidden_for_post_release_link_probe_is_reported(self):
        self.assertEqual(
            {"stage": "overlay_hidden_for_link_probe", "reason": "unknown"},
            drag_gesture_metadata("drag gesture host stage=overlay_hidden_for_link_probe"),
        )

    def test_specific_mask_validation_reason_is_preserved(self):
        self.assertEqual(
            {"stage": "takeover_failed", "reason": "drag_mask_not_touchable"},
            drag_gesture_metadata("drag gesture stage=takeover_failed reason=drag_mask_not_touchable"),
        )

    def test_legacy_aggregate_mask_reason_is_preserved(self):
        self.assertEqual(
            {"stage": "takeover_failed", "reason": "drag_mask_window"},
            drag_gesture_metadata("drag gesture stage=takeover_failed reason=drag_mask_window"),
        )

    def test_existing_capture_window_reasons_are_preserved(self):
        for reason in ("capture_hidden", "capture_bounds"):
            with self.subTest(reason=reason):
                self.assertEqual(
                    {"stage": "takeover_failed", "reason": reason},
                    drag_gesture_metadata(f"drag gesture stage=takeover_failed reason={reason}"),
                )

    def test_unknown_reason_is_redacted(self):
        self.assertEqual(
            {"stage": "takeover_failed", "reason": "unknown"},
            drag_gesture_metadata("drag gesture stage=takeover_failed reason=secret_window_detail"),
        )

    def test_legacy_exception_only_event_remains_readable(self):
        self.assertEqual(
            {"stage": "overlay_transfer_failed", "reason": "IllegalStateException"},
            drag_gesture_metadata(
                "drag gesture host stage=overlay_transfer_failed exception=IllegalStateException"
            ),
        )

if __name__ == "__main__":
    unittest.main()
