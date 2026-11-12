"""Portable profile end to end, without network: fresh dirs, fake HTTP, real HTTP server on a free port.
Run: python3 -m unittest discover -s server/tests -v"""
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from _env import TMP  # noqa: E402  (sets up the temp desk)
import pdlib  # noqa: E402
import paperdesk as pd  # noqa: E402

PAPER = {"title": "Towards Robust Railway Rescheduling", "authors": ["Ada Lovelace", "Zoë Müller"],
         "year": "2026", "venue": "arXiv", "arxiv": "2601.00001", "doi": None, "abstract": "x",
         "url": "https://arxiv.org/abs/2601.00001", "pdf_url": "https://arxiv.org/pdf/2601.00001"}


def fake_get(url, params=None, headers=None, timeout=60):
    if "arxiv.org/pdf" in url:
        return pdlib.Resp(200, b"%PDF-1.4 fake")
    if "api.openalex.org/works" in url and params:
        return pdlib.Resp(200, json.dumps({"results": [
            {"title": "A Fresh Paper on Timetables", "doi": "https://doi.org/10.1/abc", "publication_date": "2026-11-01",
             "authorships": [{"author": {"display_name": "Grace Hopper"}}], "type": "article"},
            {"title": "Towards Robust Railway Rescheduling", "doi": None, "publication_date": "2026-11-01",
             "authorships": []}]}).encode())
    if "export.arxiv.org" in url:
        return pdlib.Resp(200, b'<feed xmlns="http://www.w3.org/2005/Atom"></feed>')
    return pdlib.Resp(404, b"")


class Portable(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        pdlib.get = fake_get
        cls._orig_add = pdlib.Library.add
        pdlib.Library.add = lambda self, paper, toread=False, fetch=None: cls._orig_add(self, paper, toread, fake_get)
        cls._orig_ws = pdlib.watch_search
        pdlib.watch_search = lambda q, d, n, fetch=None: cls._orig_ws(q, d, n, fake_get)
        s = socket.socket(); s.bind(("127.0.0.1", 0)); cls.port = s.getsockname()[1]; s.close()
        threading.Thread(target=pd.serve, args=(cls.port,), daemon=True).start()
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", cls.port), timeout=0.2).close(); break
            except OSError:
                time.sleep(0.1)

    def call(self, path, body=None, token="s3cret-token"):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}",
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": f"Bearer {token}"} if token else {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if r.headers["Content-Type"].startswith("application/json") else raw.decode())
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    def wait_job(self, jid):
        for _ in range(100):
            code, j = self.call(f"/api/jobs/{jid}")
            if j["status"] in ("done", "failed"):
                return j
            time.sleep(0.1)
        self.fail("job did not finish")

    def test_1_health_and_auth(self):
        self.assertEqual(self.call("/api/health", token="wrong")[0], 401)
        self.assertEqual(self.call("/api/health", token=None)[0], 401)
        code, h = self.call("/api/health")
        self.assertEqual(code, 200)
        self.assertEqual(h["profile"], "portable")
        f = h["features"]
        self.assertEqual({k: f[k] for k in ("remarkable", "drafts", "transfer", "topics", "agent", "rm_connect",
                                            "papers_edit", "secrets", "agent_setup")},
                         {"remarkable": False, "drafts": False, "transfer": False, "topics": True, "agent": False,
                          "rm_connect": False, "papers_edit": True, "secrets": True, "agent_setup": False})
        self.assertEqual(h["targets"], [{"id": "thesis", "label": "Thesis"}])

    def test_2_pairing(self):
        self.assertEqual(self.call("/pair/AAAA-AAAA", token=None)[0], 403)
        code = pdlib.pair_new()
        status, page = self.call(f"/pair/{code}", token=None)
        self.assertEqual(status, 200)
        self.assertIn("paperdesk://pair?url=https%3A%2F%2Fdesk.example.ts.net%3A8447&amp;token=s3cret-token", page)
        status, j = self.call(f"/api/pair?code={code.lower()}", token=None)   # what the app's QR scan / manual entry uses
        self.assertEqual((status, j["url"], j["token"], j["name"]), (200, "https://desk.example.ts.net:8447", "s3cret-token", "Test desk"))
        self.assertEqual(self.call("/api/pair?code=AAAA-AAAA", token=None)[0], 403)
        for _ in range(pdlib.PAIR_MAX_FAILS):               # guessing burns every open code
            self.call("/pair/ZZZZ-ZZZZ", token=None)
        self.assertEqual(self.call(f"/pair/{code}", token=None)[0], 403)

    def test_3_include_adds_to_library_and_target(self):
        code, r = self.call("/api/action", {"id": "arxiv:2601.00001", "action": "include", "target": "thesis",
                                            "paper": PAPER})
        self.assertEqual(code, 200, r)
        j = self.wait_job(r["job"]["id"])
        self.assertEqual(j["status"], "done", j)
        lib = Path(TMP, "library/library.bib").read_text()
        self.assertIn("@misc{lovelace2026robust,", lib)
        self.assertTrue(Path(TMP, "library/pdfs/lovelace2026robust.pdf").exists())
        self.assertIn("lovelace2026robust", Path(TMP, "thesis/refs.bib").read_text())
        code, s = self.call("/api/search?q=railway+rescheduling&scope=library")
        self.assertEqual(s["library"][0]["bibkey"], "lovelace2026robust")
        code, r = self.call("/api/action", {"id": "arxiv:2601.00001", "action": "literature", "paper": PAPER})
        self.wait_job(r["job"]["id"])                       # second add: no duplicate entry
        self.assertEqual(Path(TMP, "library/library.bib").read_text().count("@misc{"), 1)

    def test_4_topics_fill_the_inbox(self):
        code, r = self.call("/api/topics", {"op": "add", "name": "Timetables", "query": "railway timetable"})
        self.assertEqual(code, 200, r)
        self.assertEqual(self.wait_job(r["job"]["id"])["status"], "done")
        code, inbox = self.call("/api/inbox")
        titles = [i["title"] for i in inbox["items"]]
        self.assertIn("A Fresh Paper on Timetables", titles)             # new
        self.assertNotIn("Towards Robust Railway Rescheduling", titles)  # already in the library
        item = next(i for i in inbox["items"] if i["title"].startswith("A Fresh"))
        self.assertEqual((item["why"], item["source"], item["status"]), ("Topic: Timetables", "watch", "new"))
        code, r = self.call("/api/topics", {"op": "run"})
        self.assertIn("0 new", self.wait_job(r["job"]["id"])["summary"])  # nothing repeats
        tid = r["topics"][0]["id"]
        self.assertEqual(self.call("/api/topics", {"op": "remove", "id": tid})[1]["topics"], [])

    def test_5_remarkable_off_is_refused(self):
        self.assertEqual(self.call("/api/rm/ls?path=/")[0], 400)
        self.assertEqual(self.call("/api/action", {"id": "x", "action": "remarkable", "paper": PAPER})[0], 400)
        self.assertEqual(self.call("/api/rm/connect", {"code": "abcdefgh"})[0], 400)


class Bib(unittest.TestCase):
    def test_keywords_and_parse(self):
        e = pdlib.build_entry("k1", dict(PAPER, venue="Transportation Research Part B", arxiv=None, doi="10.1/x"))
        self.assertTrue(e.startswith("@article{k1,"))
        e = pdlib.add_keyword(e, "toread")
        got = pdlib.parse_bib(e)[0]
        self.assertEqual((got["key"], got["keywords"], got["doi"], got["year"]), ("k1", "toread", "10.1/x", "2026"))
        self.assertEqual(pdlib.add_keyword(e, "toread"), e)


if __name__ == "__main__":
    unittest.main()
