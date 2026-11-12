"""Papers: clone, read, save, conflict, comment macros, comment list, PDF build. A local bare repo is the remote.
Run: python3 -m unittest discover -s server/tests -v"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from _env import TMP  # noqa: E402
import papers as pp  # noqa: E402

DOC = r"""\documentclass{article}
\usepackage{xcolor}
\newcommand{\rmnote}[2][]{}\newcommand{\rmhl}[1]{#1}
\begin{document}
Hello {\em railway} world. % \pdnote{not a comment: commented out}
\rmnote[p.2]{tighten this} and \rmhl{a {nested} highlight}.
\end{document}
"""


def sh(*a, cwd=None):
    subprocess.run(a, cwd=cwd, check=True, capture_output=True)


class Papers(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.remote = Path(TMP, "remote.git")
        sh("git", "init", "-q", "--bare", "-b", "master", str(cls.remote))
        seed = Path(TMP, "seed")
        sh("git", "clone", "-q", str(cls.remote), str(seed))
        (seed / "Main.tex").write_text(DOC)
        (seed / "logo.png").write_bytes(b"\x89PNG")
        sh("git", "-c", "user.email=t@t", "-c", "user.name=t", "add", "-A", cwd=seed)
        sh("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "seed", cwd=seed)
        sh("git", "push", "-q", "origin", "HEAD", cwd=seed)
        cls.seed = seed
        cls.p = {"id": "t1", "label": "Test", "url": str(cls.remote), "live": "", "main": "", "rm": False}

    def test_1_files_and_read(self):
        self.assertEqual([f["path"] for f in pp.files(self.p)], ["Main.tex"])
        r = pp.read(self.p, "Main.tex")
        self.assertEqual(r["text"], DOC)
        with self.assertRaises(ValueError):
            pp.read(self.p, "../../etc/passwd.tex")
        with self.assertRaises(ValueError):
            pp.read(self.p, "logo.png")

    def test_2_comments_parse(self):
        cs = pp.parse_comments(DOC, "Main.tex")
        self.assertEqual([(c["kind"], c["marked"], c["comment"], c["line"]) for c in cs],
                         [("rmnote", None, "tighten this (p.2)", 6), ("rmhl", "a {nested} highlight", None, 6)])

    def test_3_save_adds_macros_and_pushes(self):
        r = pp.read(self.p, "Main.tex")
        new = r["text"].replace(r"Hello {\em railway} world.",
                                r"Hello \pdmark{{\em railway} world}{say which railway}. \pdnote{add a figure}")
        res = pp.write(self.p, "Main.tex", new, r["sha"])
        self.assertTrue(res["pushed"])
        self.assertIn("pdnotes.tex", res["note"])
        log = subprocess.run(["git", "--git-dir", str(self.remote), "show", "HEAD:Main.tex"], capture_output=True,
                             text=True).stdout
        self.assertIn("\\input{pdnotes}", log)
        self.assertLess(log.index("\\input{pdnotes}"), log.index("\\begin{document}"))
        cs = pp.comments(pp.clone_dir("t1"))
        self.assertEqual([(c["kind"], c["marked"], c["comment"]) for c in cs][:2],
                         [("mark", r"{\em railway} world", "say which railway"), ("note", None, "add a figure")])
        r2 = pp.read(self.p, "Main.tex")                       # second comment: no second \input
        res = pp.write(self.p, "Main.tex", r2["text"] + "\n% x \\pdnote{y}\n", r2["sha"])
        self.assertIsNone(res["note"])

    def test_4_conflict(self):
        r = pp.read(self.p, "Main.tex")
        sh("git", "pull", "-q", cwd=self.seed)                 # someone else edits on Overleaf meanwhile
        f = self.seed / "Main.tex"
        f.write_text(f.read_text().replace("Hello", "Hi"))
        sh("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qam", "other", cwd=self.seed)
        sh("git", "push", "-q", "origin", "HEAD", cwd=self.seed)
        with self.assertRaises(pp.Conflict) as cm:
            pp.write(self.p, "Main.tex", r["text"] + "mine\n", r["sha"])
        self.assertIn("Hi ", cm.exception.text)
        ok = pp.write(self.p, "Main.tex", cm.exception.text + "mine\n", cm.exception.sha)   # "keep mine" retry
        self.assertTrue(ok["pushed"])

    @unittest.skipUnless(pp.latexmk(), "no latexmk")
    def test_5_build(self):
        import io
        log = io.StringIO()
        self.assertIn("PDF built", pp.build(self.p, log))
        self.assertTrue(pp.pdf_path(self.p).read_bytes().startswith(b"%PDF"))
        r = pp.read(self.p, "Main.tex")                         # a LaTeX error comes back readable
        pp.write(self.p, "Main.tex", r["text"].replace("\\end{document}", "\\undefinedthing\n\\end{document}"), r["sha"])
        with self.assertRaises(RuntimeError) as cm:
            pp.build(self.p, io.StringIO())
        self.assertIn("Undefined control sequence", str(cm.exception))

    def test_6_registry_refuses_frozen_copy(self):
        cfg = {"papers": [{"id": "x", "dir": "/home/joern/69c1ccc574a3b46e16cc3427"}, {"id": "y", "url": "https://h/a"}]}
        self.assertEqual(list(pp.registry(cfg, True)), ["y"])
        with self.assertRaises(ValueError):
            pp.add("x", "https://git:tok@git.overleaf.com/abc")


if __name__ == "__main__":
    unittest.main()
