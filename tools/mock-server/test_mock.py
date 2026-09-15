#!/usr/bin/env python3
"""Quick standalone checks of the offline double, not claims about Gemini intelligence."""
import unittest
from mock_gemini import analyze, speech


class MockTest(unittest.TestCase):
    def test_attribution(self):
        result = analyze('<p id="0"><q id="0.0">Hello?</q> said Alice.</p>')
        self.assertEqual(result["lines"][0]["speaker"], "alice")
        self.assertEqual(result["characters"][0]["gender"], "female")

    def test_pcm(self):
        pcm = speech("Hello reader.")
        self.assertGreater(len(pcm), 2400)
        self.assertEqual(len(pcm) % 2, 0)
        self.assertEqual(speech("Hello reader."), pcm)


if __name__ == "__main__":
    unittest.main()
