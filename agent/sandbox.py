"""Per-chat Docker sandbox for the PaperDesk agent, plus the host-side code/PR tools.

Trust model
- The agent's shell and file tools run ONLY inside container `pda-<thread>` (image pda-sandbox:1),
  as uid 1000, all capabilities dropped, no secrets, CPU/RAM/PID limits. Its /workspace is a host
  dir under /vol/scratch/agent-ws/<thread>/work, but the host never reads, writes or runs git in
  it after creating it (the agent could plant symlinks or git hooks/config there).
- Repos enter the container as a git bundle made from a host-side "pristine" clone (never
  mounted) and leave it the same way: the host fetches the agent's commits from a bundle into the
  pristine clone, checks the diff, and pushes from there with its own trusted git config.
- Pushing and opening a PR are host-side and gated by human approval (interrupt_on in agentd).
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

from deepagents.backends.protocol import ExecuteResponse, FileDownloadResponse, FileUploadResponse
from deepagents.backends.sandbox import BaseSandbox

WS_ROOT = Path("/vol/scratch/agent-ws")
IMAGE = "pda-sandbox:1"
MAX_OUTPUT = 30_000
DEFAULT_TIMEOUT = 300
MAX_TIMEOUT = 1200
MAX_RUNNING = 3
IDLE_STOP_S = 1800
LAYOUT = "2"  # container layout version: 2 = /workspace (rw) + /workspace/ro (read-only repos)
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SAFE_GIT = ["git", "-c", "core.hooksPath=/dev/null", "-c", "transfer.fsckObjects=true"]

LAST_USE: dict[str, float] = {}
_LOCK = threading.Lock()


def _run(cmd: list[str], *, input: bytes | None = None, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, input=input, capture_output=True, timeout=timeout)


def cname(tid: str) -> str:
    return f"pda-{tid}"


def ws_dir(tid: str) -> Path:
    return WS_ROOT / tid


def _state(name: str) -> str | None:
    r = _run(["docker", "inspect", "-f", "{{.State.Status}}", name], timeout=20)
    return r.stdout.decode().strip() if r.returncode == 0 else None


def ensure_container(tid: str) -> str:
    """Create/start the chat's container on demand; keep at most MAX_RUNNING running."""
    name = cname(tid)
    with _LOCK:
        st = _state(name)
        if st != "running":
            _make_room(tid)
        if st is not None and _label(name, "pda.v") != LAYOUT:  # older layout (no read-only mount): recreate
            _run(["docker", "rm", "-f", name], timeout=60)
            st = None
        if st is None:
            work = ws_dir(tid) / "work"
            ro = ws_dir(tid) / "ro"
            (work / ".home").mkdir(parents=True, exist_ok=True)
            ro.mkdir(parents=True, exist_ok=True)
            r = _run(["docker", "run", "-d", "--name", name, "--label", "pda=1", "--label", f"pda.v={LAYOUT}",
                      "--user", "1000:1000",
                      "--memory", "2g", "--memory-swap", "2g", "--cpus", "2", "--pids-limit", "512",
                      "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                      "-v", f"{work}:/workspace", "-v", f"{ro}:/workspace/ro:ro", IMAGE], timeout=60)
            if r.returncode:
                raise RuntimeError(f"sandbox start failed: {r.stderr.decode()[:300]}")
        elif st != "running":
            _run(["docker", "start", name], timeout=60)
        LAST_USE[tid] = time.time()
    return name


def _label(name: str, key: str) -> str:
    r = _run(["docker", "inspect", "-f", f'{{{{index .Config.Labels "{key}"}}}}', name], timeout=20)
    return r.stdout.decode().strip()


def _make_room(tid: str) -> None:
    r = _run(["docker", "ps", "--filter", "label=pda=1", "--format", "{{.Names}}"], timeout=20)
    running = [n.removeprefix("pda-") for n in r.stdout.decode().split() if n != cname(tid)]
    running.sort(key=lambda t: LAST_USE.get(t, 0))
    while len(running) >= MAX_RUNNING:
        _run(["docker", "stop", "-t", "3", cname(running.pop(0))], timeout=60)


def reap_idle() -> None:
    r = _run(["docker", "ps", "--filter", "label=pda=1", "--format", "{{.Names}}"], timeout=20)
    for n in r.stdout.decode().split():
        tid = n.removeprefix("pda-")
        if time.time() - LAST_USE.get(tid, 0) > IDLE_STOP_S:
            _run(["docker", "stop", "-t", "3", n], timeout=60)


def destroy(tid: str) -> None:
    _run(["docker", "rm", "-f", cname(tid)], timeout=60)
    shutil.rmtree(ws_dir(tid), ignore_errors=True)  # rmtree does not follow symlinks
    LAST_USE.pop(tid, None)


def dexec(tid: str, argv: list[str], *, input: bytes | None = None, timeout: int = DEFAULT_TIMEOUT):
    name = ensure_container(tid)
    LAST_USE[tid] = time.time()
    return _run(["docker", "exec", "-i", "-w", "/workspace", name, *argv], input=input, timeout=timeout + 15)


class DockerSandbox(BaseSandbox):
    """deepagents sandbox backend: every file/shell op runs inside the chat's container."""

    def __init__(self, tid: str):
        self.tid = tid

    @property
    def id(self) -> str:
        return cname(self.tid)

    def execute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        t = max(1, min(int(timeout or DEFAULT_TIMEOUT), MAX_TIMEOUT))
        try:
            r = dexec(self.tid, ["timeout", "-k", "5", str(t), "bash", "-lc", command], timeout=t)
        except subprocess.TimeoutExpired:
            return ExecuteResponse(output=f"[timed out after {t}s]", exit_code=124)
        out = (r.stdout + r.stderr).decode("utf-8", "replace")
        trunc = len(out) > MAX_OUTPUT
        if trunc:
            out = out[:MAX_OUTPUT // 3] + "\n...[output truncated]...\n" + out[-2 * MAX_OUTPUT // 3:]
        if r.returncode == 124:
            out += f"\n[timed out after {t}s]"
        return ExecuteResponse(output=out, exit_code=r.returncode, truncated=trunc)

    def upload_files(self, files: list[tuple[str, bytes]]) -> list[FileUploadResponse]:
        res = []
        for path, data in files:
            r = dexec(self.tid, ["sh", "-c", 'mkdir -p "$(dirname "$1")" && cat > "$1"', "sh", path],
                      input=data, timeout=60)
            err = None
            if r.returncode:
                msg = r.stderr.decode()
                err = "permission_denied" if "ermission" in msg else ("is_directory" if "s a directory" in msg
                                                                      else msg[:200] or "invalid_path")
            res.append(FileUploadResponse(path=path, error=err))
        return res

    def download_files(self, paths: list[str]) -> list[FileDownloadResponse]:
        res = []
        for path in paths:
            r = dexec(self.tid, ["cat", "--", path], timeout=60)
            if r.returncode == 0:
                res.append(FileDownloadResponse(path=path, content=r.stdout))
            else:
                msg = r.stderr.decode()
                err = ("is_directory" if "s a directory" in msg else
                       "permission_denied" if "ermission" in msg else "file_not_found")
                res.append(FileDownloadResponse(path=path, error=err))
        return res


# ----------------------------------------------------------------- host-side repo flow
def _meta_path(tid: str) -> Path:
    return ws_dir(tid) / "meta.json"


def _meta(tid: str) -> dict:
    p = _meta_path(tid)
    return json.loads(p.read_text()) if p.exists() else {"repos": {}}


def _save_meta(tid: str, m: dict) -> None:
    _meta_path(tid).write_text(json.dumps(m, indent=1))


def _host_git(pristine: Path, *args: str, timeout: int = 300) -> subprocess.CompletedProcess:
    return _run([*SAFE_GIT, "-C", str(pristine), *args], timeout=timeout)


def clone_into_sandbox(tid: str, repo: str, base_branch: str | None, identity: tuple[str, str]) -> dict:
    """Pristine clone on the host -> bundle -> clone inside the container on a fresh work branch."""
    name = repo.split("/")[1]
    m = _meta(tid) if ws_dir(tid).exists() else {"repos": {}}
    if name in m["repos"]:
        return {"already": True, **m["repos"][name]}
    if name in m.get("readonly", {}):
        raise RuntimeError(f"{name} is attached read-only in this chat; a writable copy needs a new chat")
    pristine = ws_dir(tid) / "pristine" / name
    pristine.parent.mkdir(parents=True, exist_ok=True)
    args = [*SAFE_GIT, "clone", "--single-branch", "--no-tags"]
    if base_branch:
        args += ["--branch", base_branch]
    r = _run([*args, f"git@github.com:{repo}.git", str(pristine)], timeout=600)
    if r.returncode:
        raise RuntimeError(f"clone failed: {r.stderr.decode()[-300:]}")
    base = _host_git(pristine, "rev-parse", "--abbrev-ref", "HEAD").stdout.decode().strip()
    sha = _host_git(pristine, "rev-parse", "HEAD").stdout.decode().strip()
    bundle = ws_dir(tid) / f"in-{name}.bundle"
    r = _host_git(pristine, "bundle", "create", str(bundle), base)
    if r.returncode:
        raise RuntimeError(f"bundle failed: {r.stderr.decode()[-300:]}")
    branch = f"agent/{tid}-{name}"
    dexec(tid, ["sh", "-c", "cat > /tmp/in.bundle"], input=bundle.read_bytes(), timeout=120)
    bundle.unlink()
    script = ('set -e; git clone -q -b "$3" /tmp/in.bundle "/workspace/$4"; cd "/workspace/$4"; '
              'git remote remove origin; git checkout -q -b "$5"; '
              'git config user.name "$1"; git config user.email "$2"; rm /tmp/in.bundle')
    r = dexec(tid, ["sh", "-c", script, "sh", identity[0], identity[1], base, name, branch], timeout=300)
    if r.returncode:
        raise RuntimeError(f"sandbox clone failed: {(r.stdout + r.stderr).decode()[-300:]}")
    info = {"repo": repo, "dir": f"/workspace/{name}", "mode": "write", "base_branch": base, "base_sha": sha,
            "branch": branch}
    m["repos"][name] = info
    _save_meta(tid, m)
    return info


def clone_readonly(tid: str, repo: str, ref: str | None) -> dict:
    """Shallow host-side clone into <ws>/ro/<name>, which the container sees READ-ONLY at /workspace/ro/<name>.
    The container cannot write there, so the host may safely run git in it (clone/refresh)."""
    name = repo.split("/")[1]
    m = _meta(tid) if ws_dir(tid).exists() else {"repos": {}}
    ro = m.setdefault("readonly", {})
    dest = ws_dir(tid) / "ro" / name
    if name in m.get("repos", {}):
        return {**m["repos"][name], "note": "already attached writable at /workspace/" + name}
    if name in ro:  # refresh to the latest state of the branch
        r = _run([*SAFE_GIT, "-C", str(dest), "pull", "-q", "--ff-only", "--depth", "1"], timeout=600)
        return {**ro[name], "refreshed": r.returncode == 0}
    dest.parent.mkdir(parents=True, exist_ok=True)
    args = [*SAFE_GIT, "clone", "-q", "--depth", "1", "--single-branch", "--no-tags"]
    if ref:
        args += ["--branch", ref]
    r = _run([*args, f"git@github.com:{repo}.git", str(dest)], timeout=900)
    if r.returncode:
        shutil.rmtree(dest, ignore_errors=True)
        raise RuntimeError(f"clone failed: {r.stderr.decode()[-300:]}")
    branch = _run([*SAFE_GIT, "-C", str(dest), "rev-parse", "--abbrev-ref", "HEAD"]).stdout.decode().strip()
    info = {"repo": repo, "dir": f"/workspace/ro/{name}", "mode": "read", "branch": branch}
    ro[name] = info
    _save_meta(tid, m)
    return info


def workspace(tid: str) -> list[dict]:
    m = _meta(tid) if ws_dir(tid).exists() else {}
    out = [{**v, "mode": "write"} for v in m.get("repos", {}).values()]
    return out + list(m.get("readonly", {}).values())


def export_commits(tid: str, name: str) -> dict:
    """Bundle the agent's commits out of the container into the pristine clone; return a review summary."""
    m = _meta(tid)
    if name not in m["repos"]:
        raise RuntimeError(f"'{name}' is not a writable repo in this chat (read-only repos cannot be PR'd)")
    info = m["repos"][name]
    d = info["dir"]
    st = dexec(tid, ["git", "-C", d, "status", "--porcelain"], timeout=60).stdout.decode().strip()
    if st:
        raise RuntimeError("uncommitted changes in the sandbox; commit them first:\n" + st[:800])
    n = dexec(tid, ["git", "-C", d, "rev-list", "--count", f"{info['base_sha']}..HEAD"], timeout=60)
    if n.returncode or int(n.stdout.decode().strip() or 0) == 0:
        raise RuntimeError("no new commits on top of the base branch")
    r = dexec(tid, ["sh", "-c", f'git -C "{d}" bundle create -q /tmp/out.bundle {info["base_sha"]}..HEAD '
                                f'&& cat /tmp/out.bundle && rm /tmp/out.bundle'], timeout=300)
    if r.returncode or not r.stdout:
        raise RuntimeError(f"export failed: {r.stderr.decode()[-300:]}")
    pristine = ws_dir(tid) / "pristine" / name
    bundle = ws_dir(tid) / f"out-{name}.bundle"
    bundle.write_bytes(r.stdout)
    try:
        v = _host_git(pristine, "bundle", "verify", "-q", str(bundle))
        if v.returncode:
            raise RuntimeError(f"bundle rejected: {v.stderr.decode()[-300:]}")
        f = _host_git(pristine, "fetch", "-q", str(bundle), f"+HEAD:refs/heads/{info['branch']}")
        if f.returncode:
            raise RuntimeError(f"fetch from bundle failed: {f.stderr.decode()[-300:]}")
    finally:
        bundle.unlink(missing_ok=True)
    files = _host_git(pristine, "diff", "--name-only", info["base_sha"], info["branch"]).stdout.decode().split()
    blocked = [p for p in files if p.startswith(".github/")]
    if blocked:
        raise RuntimeError(f"refused: changes under .github/ ({', '.join(blocked)}) need Joern by hand")
    stat = _host_git(pristine, "diff", "--shortstat", info["base_sha"], info["branch"]).stdout.decode().strip()
    log = _host_git(pristine, "log", "--format=%h %s", f"{info['base_sha']}..{info['branch']}").stdout.decode()
    return {**info, "files": files, "stat": stat, "commits": log.strip().splitlines()}


def push_and_pr(tid: str, name: str, title: str, body: str, draft: bool) -> dict:
    s = export_commits(tid, name)
    pristine = ws_dir(tid) / "pristine" / name
    p = _host_git(pristine, "push", "-q", "--force", "origin", f"{s['branch']}:{s['branch']}", timeout=300)
    if p.returncode:
        raise RuntimeError(f"push failed: {p.stderr.decode()[-300:]}")
    ex = _run(["gh", "pr", "list", "-R", s["repo"], "--head", s["branch"], "--state", "open",
               "--json", "url", "--jq", ".[0].url"], timeout=60).stdout.decode().strip()
    if ex:
        return {**s, "url": ex, "updated": True}
    args = ["gh", "pr", "create", "-R", s["repo"], "--head", s["branch"], "--base", s["base_branch"],
            "--title", title, "--body", body]
    if draft:
        args.append("--draft")
    r = _run(args, timeout=120)
    if r.returncode:
        raise RuntimeError(f"gh pr create failed: {r.stderr.decode()[-300:]}")
    return {**s, "url": r.stdout.decode().strip().splitlines()[-1], "updated": False}


def reaper_loop() -> None:
    while True:
        time.sleep(300)
        try:
            reap_idle()
        except Exception:
            pass
