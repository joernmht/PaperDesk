"""GitHub tools for the PaperDesk agent (host-side, via the box's `gh` login).

Read tools work on any repo the login can see, except the deny list. Write tools (comment, issue,
pull request) are limited to `write_owners`, and agentd gates every one behind Joern's approval.
"""
from __future__ import annotations

import base64
import json
import subprocess
from urllib.parse import quote

from langchain_core.tools import tool

import sandbox

MAX = 20_000


class Policy:
    def __init__(self, cfg: dict):
        self.deny = {r.lower() for r in cfg.get("deny", [])}
        self.write_owners = {o.lower() for o in cfg.get("write_owners", ["joernmht"])}
        self.default_owner = cfg.get("default_owner", "joernmht")
        self.identity = (cfg.get("author_name", "Joern Maurischat"), cfg.get("author_email", ""))

    def check(self, repo: str, write: bool = False) -> str:
        repo = repo.strip().removeprefix("https://github.com/").removesuffix(".git").strip("/")
        if "/" not in repo:  # models often drop the owner
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


def make_tools(tid: str, policy: Policy) -> tuple[list, dict]:
    """Return (tools, interrupt_on) for one chat thread."""

    @tool
    def github_list_repos(owner: str = "joernmht", limit: int = 40) -> str:
        """List a GitHub owner's repositories (name, visibility, description, last push)."""
        try:
            return _gh("repo", "list", owner, "--limit", str(min(limit, 100)), "--json",
                       "nameWithOwner,visibility,description,pushedAt,isFork,isArchived")
        except Exception as e:
            return _err(e)

    @tool
    def github_repo(repo: str) -> str:
        """Overview of one repo: description, default branch, open issue/PR counts, latest CI runs."""
        try:
            repo = policy.check(repo)
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
            repo = policy.check(repo)
            return _gh("issue", "list", "-R", repo, "--state", state, "--limit", str(min(limit, 100)),
                       "--json", "number,title,state,author,labels,updatedAt,comments")
        except Exception as e:
            return _err(e)

    @tool
    def github_issue(repo: str, number: int) -> str:
        """Read one issue with its comments."""
        try:
            repo = policy.check(repo)
            return _gh("issue", "view", str(int(number)), "-R", repo, "--json",
                       "number,title,state,author,body,labels,comments,url,createdAt")
        except Exception as e:
            return _err(e)

    @tool
    def github_prs(repo: str, state: str = "open", limit: int = 20) -> str:
        """List pull requests of a repo. state: open | closed | merged | all."""
        try:
            repo = policy.check(repo)
            return _gh("pr", "list", "-R", repo, "--state", state, "--limit", str(min(limit, 100)),
                       "--json", "number,title,state,author,headRefName,isDraft,updatedAt,url")
        except Exception as e:
            return _err(e)

    @tool
    def github_pr(repo: str, number: int, with_diff: bool = False) -> str:
        """Read one pull request: description, files, checks, reviews; optionally the diff."""
        try:
            repo = policy.check(repo)
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
            repo = policy.check(repo)
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
            repo = policy.check(repo)
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
        """Post a comment on an issue or pull request. Needs Joern's approval."""
        try:
            repo = policy.check(repo, write=True)
            out = _gh("api", f"repos/{repo}/issues/{int(number)}/comments", "-f",
                      f"body={body}\n\n<sub>Posted by the PaperDesk agent on Joern's approval.</sub>",
                      "--jq", ".html_url")
            return f"Comment posted: {out.strip()}"
        except Exception as e:
            return _err(e)

    @tool
    def github_create_issue(repo: str, title: str, body: str) -> str:
        """Open a new issue. Needs Joern's approval."""
        try:
            repo = policy.check(repo, write=True)
            out = _gh("issue", "create", "-R", repo, "--title", title, "--body",
                      body + "\n\n<sub>Opened by the PaperDesk agent on Joern's approval.</sub>")
            return f"Issue opened: {out.strip().splitlines()[-1]}"
        except Exception as e:
            return _err(e)

    # ---------------------------------------------------------- code in the sandbox
    @tool
    def clone_repo(repo: str, base_branch: str = "") -> str:
        """Clone one of Joern's GitHub repos into this chat's sandbox at /workspace/<name>, on a
        fresh work branch. Then edit, test and `git commit` there with the execute tool."""
        try:
            repo = policy.check(repo, write=True)
            info = sandbox.clone_into_sandbox(tid, repo, base_branch or None, policy.identity)
            return json.dumps(info) + ("\n(already cloned in this chat)" if info.get("already") else "")
        except Exception as e:
            return _err(e)

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
        a pull request. Needs Joern's approval. Commit everything first; changes to .github/ are refused."""
        try:
            m = sandbox._meta(tid)["repos"].get(repo_dir_name)
            if not m:
                raise RuntimeError(f"no cloned repo '{repo_dir_name}' in this chat")
            policy.check(m["repo"], write=True)
            res = sandbox.push_and_pr(tid, repo_dir_name, title, body + "\n\n---\n🤖 Opened by the PaperDesk "
                                      "agent (code written and tested in its sandbox) on Joern's approval.", draft)
            return json.dumps({k: res[k] for k in ("url", "updated", "branch", "stat", "commits")})
        except Exception as e:
            return _err(e)

    tools = [github_list_repos, github_repo, github_issues, github_issue, github_prs, github_pr,
             github_read_file, github_ci_runs, github_comment, github_create_issue,
             clone_repo, review_changes, open_pull_request]
    gate = {"allowed_decisions": ["approve", "reject"]}
    interrupt_on = {"github_comment": gate, "github_create_issue": gate, "open_pull_request": gate}
    return tools, interrupt_on
