"""One temp desk for all server tests (pdlib reads its dirs once per process)."""
import os
import sys
import tempfile
from pathlib import Path

TMP = os.environ.get("PDTEST_TMP") or tempfile.mkdtemp(prefix="pdtest-")
os.environ["PDTEST_TMP"] = TMP
os.environ["PAPERDESK_CONFIG_DIR"] = TMP + "/config"
os.environ["PAPERDESK_STATE_DIR"] = TMP + "/state"
Path(TMP, "config").mkdir(exist_ok=True)
Path(TMP, "config/token").write_text("s3cret-token\n")
Path(TMP, "config/config.toml").write_text(f'''
name = "Test desk"
public_url = "https://desk.example.ts.net:8447"
[library]
dir = "{TMP}/library"
[remarkable]
enabled = false
[[targets]]
id = "thesis"
label = "Thesis"
bib = "{TMP}/thesis/refs.bib"
''')
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
