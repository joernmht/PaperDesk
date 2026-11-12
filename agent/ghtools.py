"""GitHub tools for the PaperDesk agent (host-side, via the box's `gh` login).

Read tools work on any repo the login can see, except the deny list. Write tools (comment, issue,
writable clone, pull request) are limited to `write_owners`; in "ask" chats agentd gates them behind
the user's approval, in "auto" chats (the default) they run directly.
On the box the login is the box's `gh` login; on a portable install `GH_TOKEN` (from GITHUB_TOKEN).
"""
from __future__ import annotations

import base64
import json
import re
import subprocess
from urllib.parse import quote

from langchain_core.tools import tool

import sandbox

MAX = 20_000
GATED = {"github_comment", "github_create_issue", "open_pull_request", "clone_repo_writable"}


class Policy:
    def __init__(self, cfg: dict):
        self.deny = {r.lower() for r in cfg.get("deny", [])}
        self.write_owners = {o.lower() for o in cfg.get("write_owners", [])}
        self.default_owner = cfg.get("default_owner", "")
        self.identity = (cfg.get("author_name", "PaperDesk agent"), cfg.get("author_email", ""))
        self.user = cfg.get("user", "the user")    # how messages name the person ("Joern" on the box)
        self.he = cfg.get("he", "they")

    def check(self, repo: str, write: bool = False) -> str:
        repo = repo.strip().removeprefix("https://github.com/").removesuffix(".git").strip("/")
        if "/" not in repo:  # models often drop the owner
            if not self.default_owner:
                raise ValueError(f"not a repo name: {repo!r} (use owner/name)")
            repo = f"{self.default_owner}/{repo}"
        if not sandbox.REPO_RE.match(repo):
            raise ValueError(f"not a repo name: {repo!r} (use owner/name)")
        if repo.lower() in self.deny:
            raise PermissionError(f"{repo} is off-limits for the agent")
        if write and repo.split("/")[0].lower() not in self.write_owners:
            raise PermissionError(f"write access only for {sorted(self.write_owners)} repos")
        return repo


def _gh(*args: str, timeout: int = 60) -> str:
    r = subprocess.run(["gh", *args], capture_output=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError(r.stderr.decode("utf-8", "replace").strip()[:500] or "gh failed")
    out = r.stdout.decode("utf-8", "replace")
    return out if len(out) <= MAX else out[:MAX] + "\n...[truncated]"


def _err(e: Exception) -> str:
    return f"ERROR: {e}"


BROAD = re.compile(r"\b(all|every|other|which|any)\b[^.?!\n]{0,25}\brepo", re.I)


def scope_error(repo: str, allowed: set[str], user: str = "the user", he: str = "they") -> str:
    return (f"{repo} is outside this chat: {user} limited it to {', '.join(sorted(allowed))}. Work only with "
            f"those. If another repo is really needed, say so in your answer; {he} can add it in the app.")


def make_tools(tid: str, policy: Policy, scope=None) -> tuple[list, dict]:
    """Return (tools, interrupt_on) for one chat thread.

    `scope()` -> (attached repos, lowercased text of Joern's messages in this chat). When repos are attached
    they are the chat's whole world: other repos only if his messages name them (Joern 2026-11-12: he picked
    one repo for a presentation and the agent scanned all of them)."""

    def chk(repo: str, write: bool = False) -> str:
        repo = policy.check(repo, write)
        if scope is None:
            return repo
        allowed, text = scope()
        name = repo.split("/")[1].lower()
        if (not allowed or repo.lower() in allowed or BROAD.search(text)
                or re.search(rf"(?<![\w-]){re.escape(name)}(?![\w-])", text)):
            return repo
        raise PermissionError(scope_error(repo, allowed, policy.user, policy.he))

    @tool
    def github_list_repos(owner: str = "", limit: int = 40) -> str:
        """List a GitHub owner's repositories (name, visibility, description, last push)."""
        if scope is not None:
            allowed, text = scope()
            if allowed and not BROAD.search(text):
                return (f"ERROR: not needed: this chat is limited to the repos {policy.user} attached: "
                        + ", ".join(sorted(allowed)) + ". Use those.")
        try:
            owner = owner or policy.default_owner
            return _gh("repo", "list", *([owner] if owner else []), "--limit", str(min(limit, 100)), "--json",
                       "nameWithOwner,visibility,description,pushedAt,isFork,isArchived")
        except Exception as e:
            return _err(e)

    @tool
    def github_repo(repo: str) -> str:
        """Overview of one repo: description, default branch, open issue/PR counts, latest CI runs."""
        try:
            repo = chk(repo)
            v = json.loads(_gh("repo", "view", repo, "--json",
                               "nameWithOwner,description,visibility,defaultBranchRef,pushedAt,url,"
                               "issues,pullRequests,licenseInfo,primaryLanguage"))
            try:
                runs = json.loads(_gh("run", "list", "-R", repo, "-L", "5", "--json",
                                      "workflowName,headBranch,status,conclusion,createdAt,url"))
            except Exception:
                runs = []
            v["latest_ci_runs"] = runs
            return json.dumps(v)[:MAX]
        except Exception as e:
            return _err(e)

    @tool
    def github_issues(repo: str, state: str = "open", limit: int = 20) -> str:
        """List issues of a repo. state: open | closed | all."""
        try:
            repo = chk(repo)
            return _gh("issue", "list", "-R", repo, "--state", state, "--limit", str(min(limit, 100)),
                       "--json", "number,title,state,author,labels,updatedAt,comments")
        except Exception as e:
            return _err(e)

    @tool
    def github_issue(repo: str, number: int) -> str:
        """Read one issue with its comments."""
        try:
            repo = chk(repo)
            return _gh("issue", "view", str(int(number)), "-R", repo, "--json",
                       "number,title,state,author,body,labels,comments,url,createdAt")
        except Exception as e:
            return _err(e)

    @tool
    def github_prs(repo: str, state: str = "open", limit: int = 20) -> str:
        """List pull requests of a repo. state: open | closed | merged | all."""
        try:
            repo = chk(repo)
            return _gh("pr", "list", "-R", repo, "--state", state, "--limit", str(min(limit, 100)),
                       "--json", "number,title,state,author,headRefName,isDraft,updatedAt,url")
        except Exception as e:
            return _err(e)

    @tool
    def github_pr(repo: str, number: int, with_diff: bool = False) -> str:
        """Read one pull request: description, files, checks, reviews; optionally the diff."""
        try:
            repo = chk(repo)
            out = _gh("pr", "view", str(int(number)), "-R", repo, "--json",
                      "number,title,state,isDraft,author,body,headRefName,baseRefName,files,additions,"
                      "deletions,statusCheckRollup,reviews,comments,url")
            if with_diff:
                out += "\n\nDIFF:\n" + _gh("pr", "diff", str(int(number)), "-R", repo)
            return out[:MAX]
        except Exception as e:
            return _err(e)

    @tool
    def github_read_file(repo: str, path: str = "", ref: str = "") -> str:
        """Read a file from a repo, or list a directory (path '' = repo root). ref = branch/tag/sha."""
        try:
            repo = chk(repo)
            path = path.strip().lstrip("/")
            if ".." in path.split("/"):
                raise ValueError("no '..' in paths")
            url = f"repos/{repo}/contents/{quote(path)}" + (f"?ref={quote(ref)}" if ref else "")
            data = json.loads(_gh("api", url))
            if isinstance(data, list):
                return "\n".join(f"{'d' if e['type'] == 'dir' else 'f'} {e['path']}" for e in data)
            if data.get("encoding") == "base64":
                text = base64.b64decode(data["content"]).decode("utf-8", "replace")
                return text if len(text) <= MAX else text[:MAX] + "\n...[truncated]"
            return json.dumps({k: data.get(k) for k in ("path", "size", "download_url")})
        except Exception as e:
            return _err(e)

    @tool
    def github_ci_runs(repo: str, branch: str = "", limit: int = 10) -> str:
        """Recent GitHub Actions runs (status, conclusion); optionally for one branch."""
        try:
            repo = chk(repo)
            args = ["run", "list", "-R", repo, "-L", str(min(limit, 50)), "--json",
                    "databaseId,workflowName,headBranch,event,status,conclusion,createdAt,url"]
            if branch:
                args += ["--branch", branch]
            return _gh(*args)
        except Exception as e:
            return _err(e)

    # ---------------------------------------------------------- writes (approval-gated)
    @tool
    def github_comment(repo: str, number: int, body: str) -> str:
        """Post a comment on an issue or pull request."""
        try:
            repo = chk(repo, write=True)
            out = _gh("api", f"repos/{repo}/issues/{int(number)}/comments", "-f",
                      f"body={body}\n\n<sub>Posted by the PaperDesk agent for {policy.user}.</sub>",
                      "--jq", ".html_url")
            return f"Comment posted: {out.strip()}"
        except Exception as e:
            return _err(e)

    @tool
    def github_create_issue(repo: str, title: str, body: str) -> str:
        """Open a new issue."""
        try:
            repo = chk(repo, write=True)
            out = _gh("issue", "create", "-R", repo, "--title", title, "--body",
                      body + f"\n\n<sub>Opened by the PaperDesk agent for {policy.user}.</sub>")
            return f"Issue opened: {out.strip().splitlines()[-1]}"
        except Exception as e:
            return _err(e)

    # ---------------------------------------------------------- code in the sandbox
    @tool
    def clone_repo_readonly(repo: str, ref: str = "") -> str:
        """Attach a GitHub repo READ-ONLY at /workspace/ro/<name> (latest commit of its default branch, or
        `ref`). Use it for reference material: style guides, templates, other code. You cannot change it.
        Calling it again refreshes the copy."""
        try:
            repo = chk(repo)
            return json.dumps(sandbox.clone_readonly(tid, repo, ref or None))
        except Exception as e:
            return _err(e)

    @tool
    def clone_repo_writable(repo: str, base_branch: str = "") -> str:
        """Attach one of the user's repos WRITABLE at /workspace/<name> on a fresh work branch, for changes
        that end in a pull request. Skip it if the repo is already attached writable."""
        try:
            repo = chk(repo, write=True)
            info = sandbox.clone_into_sandbox(tid, repo, base_branch or None, policy.identity)
            return json.dumps(info) + ("\n(already attached in this chat)" if info.get("already") else "")
        except Exception as e:
            return _err(e)

    @tool
    def list_workspace() -> str:
        """List the repos attached to this chat with their path and mode (write | read)."""
        return json.dumps(sandbox.workspace(tid)) or "[]"

    @tool
    def review_changes(repo_dir_name: str) -> str:
        """Show what a pull request from the sandbox repo would contain (commits, files, diffstat),
        without pushing anything. Run this before open_pull_request."""
        try:
            return json.dumps(sandbox.export_commits(tid, repo_dir_name))
        except Exception as e:
            return _err(e)

    @tool
    def open_pull_request(repo_dir_name: str, title: str, body: str, draft: bool = True) -> str:
        """Push the committed work branch of /workspace/<repo_dir_name> to GitHub and open (or update)
        a draft pull request. Commit everything first; changes to .github/ are refused."""
        try:
            m = sandbox._meta(tid)["repos"].get(repo_dir_name)
            if not m:
                raise RuntimeError(f"'{repo_dir_name}' is not a writable repo in this chat")
            chk(m["repo"], write=True)
            res = sandbox.push_and_pr(tid, repo_dir_name, title, body + "\n\n---\n🤖 Opened by the PaperDesk "
                                      f"agent for {policy.user} (code written and tested in its sandbox).", draft)
            return json.dumps({k: res[k] for k in ("url", "updated", "branch", "stat", "commits")})
        except Exception as e:
            return _err(e)

    tools = [github_list_repos, github_repo, github_issues, github_issue, github_prs, github_pr,
             github_read_file, github_ci_runs, github_comment, github_create_issue,
             clone_repo_readonly, clone_repo_writable, list_workspace, review_changes, open_pull_request]
    gate = {"allowed_decisions": ["approve", "reject"]}
    interrupt_on = {name: gate for name in GATED}
    return tools, interrupt_on
