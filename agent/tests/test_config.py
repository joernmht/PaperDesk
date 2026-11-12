"""Offline tests for the agent's profiles and live config (no LLM, no Docker, no GitHub).

Run with the agent venv:  <venv>/bin/python -m unittest discover -s agent/tests -v
Each case imports agentd in a fresh subprocess with its own PAPERDESK_CONFIG_DIR / PAPERDESK_STATE_DIR,
because the profile is decided at import time.
"""
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

AGENT = Path(__file__).resolve().parent.parent


def run(code: str, cfg: Path, state: Path, **env) -> dict:
    """Run `code` after `import agentd`; it must print one JSON line (the last line of stdout)."""
    prog = "import sys, json, os, time\nsys.path.insert(0, %r)\nimport agentd, sandbox\n" % str(AGENT)
    prog += textwrap.dedent(code)
    e = {k: v for k, v in os.environ.items() if k not in ("LLM_API_KEY", "GITHUB_TOKEN", "GH_TOKEN")}
    e.update(PAPERDESK_CONFIG_DIR=str(cfg), PAPERDESK_STATE_DIR=str(state), **env)
    r = subprocess.run([sys.executable, "-I", "-c", prog], capture_output=True, text=True, env=e, timeout=120)
    if r.returncode:
        raise AssertionError(r.stderr[-3000:])
    return json.loads(r.stdout.strip().splitlines()[-1])


class Portable(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="pda-test-"))
        self.cfg, self.state = self.tmp / "config", self.tmp / "state"
        self.cfg.mkdir()
        (self.cfg / "token").write_text("t0k\n")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_unconfigured_then_live_reload(self):
        out = run("""
            from fastapi import HTTPException
            h = agentd.health()
            res = {"box": agentd.BOX, "configured": h["configured"], "problems": h["problems"],
                   "providers": {p["id"]: p["eu"] for p in h["providers"]}, "model": h["model"],
                   "ws": str(sandbox.WS_ROOT), "git": sandbox.GIT_URL, "roots": [str(r) for r in sandbox.CHECKOUT_ROOTS],
                   "prefix": sandbox.PREFIX}
            try:
                agentd.new_thread(agentd.NewThread())
            except HTTPException as e:
                res["new_thread"] = e.status_code
            agentd.AGENTS[("x", "m", "auto", 1)] = object()
            cfg = agentd.CONFIG_DIR
            (cfg / "agent.toml").write_text('base_url = "https://api.mistral.ai/v1"\\nmodel = "mistral-large-latest"\\n'
                                            'models = ["mistral-small-latest"]\\neu_only = true\\n')
            (cfg / "secrets.env").write_text("LLM_API_KEY=sk-test\\n")
            h = agentd.health()
            res.update(after=h["configured"], after_model=h["model"], models=h["models"], hosting=h["hosting"],
                       region=h["region"], cache=len(agentd.AGENTS), key=os.environ.get("LLM_API_KEY"),
                       prompt_clean=all(w not in agentd.system_prompt() for w in ("Joern", "joernmht", "TU Dresden")),
                       user=agentd.POLICY.user, owner=agentd.POLICY.default_owner)
            t = agentd.new_thread(agentd.NewThread())
            res["thread_model"] = t["model"]
            (cfg / "agent.toml").write_text('base_url = "https://example.com/v1"\\nmodel = "m2"\\neu_only = true\\n')
            st = (cfg / "agent.toml").stat(); os.utime(cfg / "agent.toml", ns=(st.st_atime_ns, st.st_mtime_ns + 10**9))
            res.update(eu_bad=agentd.configured(), eu_problems=agentd.PROBLEMS)
            (cfg / "agent.toml").write_text('base_url = "https://example.com/v1"\\nmodel = "m2"\\n')
            st = (cfg / "agent.toml").stat(); os.utime(cfg / "agent.toml", ns=(st.st_atime_ns, st.st_mtime_ns + 2 * 10**9))
            res.update(open_ok=agentd.configured(), open_hosting=agentd.HOSTING, open_region=agentd.health()["region"])
            (cfg / "secrets.env").write_text("")
            st = (cfg / "secrets.env").stat(); os.utime(cfg / "secrets.env", ns=(st.st_atime_ns, st.st_mtime_ns + 10**9))
            res.update(nokey=agentd.configured(), nokey_env=os.environ.get("LLM_API_KEY"))
            print(json.dumps(res))
        """, self.cfg, self.state, PAPERDESK_AGENT_PREFIX="pdt")
        self.assertFalse(out["box"])
        self.assertFalse(out["configured"])
        self.assertIn("no LLM provider chosen", out["problems"])
        self.assertEqual(out["new_thread"], 503)
        self.assertEqual(out["providers"], {"mistral": True, "scads": True, "openrouter-eu": True, "other": False})
        self.assertIsNone(out["model"])
        self.assertEqual((out["ws"], out["roots"], out["prefix"]), (str(self.state / "agent-ws"), [], "pdt"))
        self.assertTrue(out["git"].startswith("https://github.com/"))
        # reload without restart
        self.assertTrue(out["after"])
        self.assertEqual(out["after_model"], "mistral-large-latest")
        self.assertEqual(out["models"], ["mistral-large-latest", "mistral-small-latest"])
        self.assertEqual((out["hosting"], out["region"]), ("Mistral AI (France)", "EU"))
        self.assertEqual(out["cache"], 0)              # cached agents dropped on reload
        self.assertEqual(out["key"], "sk-test")
        self.assertTrue(out["prompt_clean"])
        self.assertEqual((out["user"], out["owner"]), ("the user", ""))
        self.assertEqual(out["thread_model"], "mistral-large-latest")
        # eu_only refuses a non-EU host, without eu_only it is accepted
        self.assertFalse(out["eu_bad"])
        self.assertTrue(any("allowlist" in p for p in out["eu_problems"]))
        self.assertTrue(out["open_ok"])
        self.assertEqual((out["open_hosting"], out["open_region"]), ("example.com", None))
        # removing the key un-configures it again and clears the env
        self.assertFalse(out["nokey"])
        self.assertIsNone(out["nokey_env"])

    def test_box_switch(self):
        (self.cfg / "config.toml").write_text('profile = "box"\n')
        out = run("""
            print(json.dumps({"box": agentd.BOX, "configured": agentd.configured(), "user": agentd.POLICY.user,
                              "owner": agentd.POLICY.default_owner, "git": sandbox.GIT_URL,
                              "same_prompt": agentd.system_prompt() == agentd.SYSTEM_PROMPT}))
        """, self.cfg, self.state)
        self.assertEqual(out, {"box": True, "configured": True, "user": "Joern", "owner": "joernmht",
                               "git": "git@github.com:{repo}.git", "same_prompt": True})

    def test_portable_prompt_edits_apply(self):
        out = run("""
            p = agentd.PORTABLE_PROMPT
            print(json.dumps({"n": len(agentd._PORTABLE_EDITS), "joern": "Joern" in p, "his": " his " in p,
                              "account": "{account}" in p}))
        """, self.cfg, self.state)
        self.assertEqual(out, {"n": 8, "joern": False, "his": False, "account": True})


if __name__ == "__main__":
    unittest.main()
