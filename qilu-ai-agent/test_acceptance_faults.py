from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from app.acceptance_faults import (
    acceptance_faults_enabled,
    consume_checkpoint_interrupt_after_tools,
    fault_status,
    force_agent_http_500,
    force_agent_invalid_json,
)
import app.acceptance_faults as acceptance_faults


class AcceptanceFaultsTest(unittest.TestCase):

    def test_switches_are_ignored_outside_acceptance_profile(self):
        values = {
            "APP_PROFILE": "production",
            "QILU_ACCEPTANCE_FAULTS_ENABLED": "true",
            "QILU_ACCEPTANCE_AGENT_HTTP_500": "true",
            "QILU_ACCEPTANCE_AGENT_INVALID_JSON": "true",
        }
        with patch.dict(os.environ, values, clear=False):
            self.assertFalse(acceptance_faults_enabled())
            self.assertFalse(force_agent_http_500())
            self.assertFalse(force_agent_invalid_json())

    def test_switches_are_visible_in_acceptance_profile(self):
        values = {
            "APP_PROFILE": "acceptance",
            "QILU_ACCEPTANCE_FAULTS_ENABLED": "true",
            "QILU_ACCEPTANCE_AGENT_DELAY_MS": "25",
            "QILU_ACCEPTANCE_AGENT_HTTP_500": "true",
            "QILU_ACCEPTANCE_AGENT_INVALID_JSON": "false",
            "QILU_ACCEPTANCE_TOOL_DELAY_MS": "30",
            "QILU_ACCEPTANCE_CHECKPOINT_INTERRUPT_AFTER_TOOLS_ONCE": "true",
        }
        with patch.dict(os.environ, values, clear=False):
            acceptance_faults._checkpoint_interrupt_consumed = False
            status = fault_status()
            self.assertTrue(status["acceptanceFaultsEnabled"])
            self.assertEqual(25, status["agentDelayMs"])
            self.assertTrue(status["agentHttp500"])
            self.assertFalse(status["agentInvalidJson"])
            self.assertEqual(30, status["toolDelayMs"])
            self.assertTrue(status["checkpointInterruptAfterToolsOnce"])
            self.assertTrue(consume_checkpoint_interrupt_after_tools())
            self.assertFalse(consume_checkpoint_interrupt_after_tools())


if __name__ == "__main__":
    unittest.main()
