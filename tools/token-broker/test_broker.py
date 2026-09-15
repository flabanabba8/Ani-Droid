"""No Google credentials or network calls; exercise broker access control and refresh cache."""
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
from http.server import ThreadingHTTPServer

from broker import Tokens, handler


class BrokerTest(unittest.TestCase):
    def test_cached_and_forced_refresh(self):
        with tempfile.TemporaryDirectory() as directory:
            adc = Path(directory) / "adc.json"
            adc.write_text(json.dumps(dict(type="authorized_user", client_id="fake", client_secret="fake", refresh_token="fake")))
            tokens = Tokens(adc)
            def reply(*args, **kwargs):
                return io.BytesIO(b'{"access_token":"fake-short-token","expires_in":3600}')
            with patch("broker.urllib.request.urlopen", side_effect=reply) as refresh:
                self.assertEqual("fake-short-token", tokens.get()["access_token"])
                tokens.get()
                self.assertEqual(1, refresh.call_count)
                tokens.get(force=True)
                self.assertEqual(2, refresh.call_count)
                tokens.expiry = 0
                tokens.get()
                self.assertEqual(3, refresh.call_count)

    def test_only_paired_requests_can_mint(self):
        class FakeTokens:
            calls = []
            def get(self, force=False):
                self.calls.append(force)
                return {"access_token": "fake-short-token", "expires_in": 3600}
        tokens = FakeTokens()
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler({"secret": "test-pairing", "project": "test-project"}, tokens))
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = "http://127.0.0.1:%s" % server.server_port
        try:
            for path, auth, expected in (("/token", "", 401), ("/token", "Bearer wrong", 401), ("/other", "Bearer test-pairing", 404)):
                request = urllib.request.Request(base + path, b"", {"Authorization": auth})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request)
                self.assertEqual(expected, error.exception.code)
                error.exception.close()
            self.assertEqual([], tokens.calls)
            request = urllib.request.Request(base + "/token", b"", {"Authorization": "Bearer test-pairing", "X-Reader-Refresh": "1"})
            with urllib.request.urlopen(request) as response:
                self.assertEqual("no-store", response.headers["Cache-Control"])
                self.assertEqual("test-project", json.load(response)["project"])
            self.assertEqual([True], tokens.calls)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
