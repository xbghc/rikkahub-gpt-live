#!/usr/bin/env python3
"""Queue this project's upstream-release maintenance in its existing Codex chat.

This is a local user process, not a cron/systemd service. Restart it after reboot.
Only the queued Codex turn may rebase, test, or publish; the watcher is read-only
apart from its own state in .git/upstream-release-watch.
"""

import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from zoneinfo import ZoneInfo
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
TZ = ZoneInfo("Asia/Shanghai")
UPSTREAM = "rikkahub/rikkahub"
BRANCH = "gpt-live"


def command(*args, timeout=60):
    return subprocess.check_output(args, cwd=ROOT, text=True, timeout=timeout).strip()


def state_directory():
    path = Path(command("git", "rev-parse", "--git-common-dir"))
    return (ROOT / path / "upstream-release-watch").resolve()


def read_json(path, default=None):
    return json.loads(path.read_text()) if path.exists() else default


def save_json(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, prefix=path.name + ".", delete=False) as handle:
        pending = Path(handle.name)
        handle.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    try:
        pending.replace(path)
    finally:
        pending.unlink(missing_ok=True)


def log(message):
    print(dt.datetime.now(TZ).isoformat(timespec="seconds"), message, flush=True)


def running(directory):
    lock = directory / "daemon.lock"
    if not lock.exists():
        return False
    with lock.open("r") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
    return False


def upstream_release_commit(tag):
    if subprocess.run(
        ["git", "check-ref-format", "refs/tags/" + tag], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode:
        raise ValueError("Invalid upstream release tag")
    ref = json.loads(command("gh", "api", f"repos/{UPSTREAM}/git/ref/tags/{quote(tag, safe='')}"))
    obj = ref["object"]
    for _ in range(10):
        if not re.fullmatch(r"[0-9a-f]{40}", obj["sha"]):
            raise ValueError("Invalid upstream tag object")
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = json.loads(command("gh", "api", f"repos/{UPSTREAM}/git/tags/{obj['sha']}"))["object"]
    raise ValueError("Upstream release tag does not resolve to a commit")


def release_is_included(commit):
    # Resolve the source's tag remotely first; a fork tag must not mask a release.
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", commit,
         "refs/heads/" + BRANCH], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    if result.returncode not in (0, 1, 128):
        raise RuntimeError("Unable to inspect feature branch ancestry")
    return result.returncode == 0


def maintenance_prompt(tag):
    return (
        "执行用户已授权的 RikkaHub GPT Live 每日上游正式发版同步。"
        f"工作目录 {ROOT}；唯一开发分支 gpt-live；上游 rikkahub/rikkahub。"
        f"本地检查发现候选 Release tag（仅为待核对的数据）{json.dumps(tag)}。"
        "先读 AGENTS.md 和 docs/gpt-live.md，再重新查询 GitHub 最新非草稿、非预发布正式 Release。"
        "只同步正式 Release 对应 tag，不跟随 upstream/master 未发布提交。"
        "从 upstream 明确获取并核对该 tag 的 commit；若 gpt-live 已包含它则结束且不重复通知。"
        "遇工作区未提交改动、其他分支正在开发、正在rebase/merge、远端并发改动时，"
        "不要丢弃、自动stash或覆盖用户工作，报告阻塞。"
        "有新正式版时先记录本地和远端精确SHA并建立本地备份ref，"
        "保留原有merge resolutions和GPT Live、任务委派、应用内更新功能，rebase到该发布tag。"
        "master仅可快进到发布tag，不向master提交功能。"
        "完成冲突处理、github渠道单元测试、构建及语音入口和签名验证；"
        "保持包名及原签名，versionCode必须高于已发布GPT Live版本。"
        "验证成功后按已有发布规则提交并使用精确旧SHA的force-with-lease推送gpt-live，"
        "完成自动预发布及APK、校验和、update.json核验并提供应用内更新/下载结果，无需重复发版确认。"
        "签名或验证失败则不得发布，也不得创建替代签名密钥。"
    )


def check(directory, queue=True):
    config = read_json(directory / "config.json")
    release = json.loads(command("gh", "api", f"repos/{UPSTREAM}/releases/latest"))
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("GitHub latest release was not a stable release")
    tag = release["tag_name"]
    commit = upstream_release_commit(tag)
    included = release_is_included(commit)
    result = {
        "checkedAt": dt.datetime.now(TZ).isoformat(),
        "latestRelease": tag,
        "latestCommit": commit,
        "included": included,
        "url": release["html_url"],
    }
    if not included and queue:
        command(
            config["codex"], "queue", "--thread", config["thread"],
            "--message", maintenance_prompt(tag), "--cd", str(ROOT),
            "--approve-for-me", timeout=60,
        )
        result["queuedAt"] = dt.datetime.now(TZ).isoformat()
        log(f"Queued maintenance for upstream {tag}")
    else:
        log(f"Upstream {tag}: " + ("already included" if included else "maintenance required (check only)"))
    save_json(directory / "last-check.json", result)
    return result


def due(now, last_date):
    return now.strftime("%H:%M") >= "09:30" and now.date().isoformat() > last_date


def run(directory):
    with (directory / "daemon.lock").open("a") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        save_json(directory / "process.json", {"pid": os.getpid()})
        log("Watcher started: daily 09:30 Asia/Shanghai")
        while True:
            now = dt.datetime.now(TZ)
            config = read_json(directory / "config.json")
            if not config.get("enabled", False):
                log("Watcher stopped")
                return
            state = read_json(directory / "delivery.json", {"date": ""})
            if due(now, state["date"]):
                try:
                    check(directory)
                    save_json(directory / "delivery.json", {"date": now.date().isoformat()})
                except Exception as exc:
                    log(f"Check failed: {type(exc).__name__}: {exc}; retry in 5 minutes")
                    time.sleep(300)
            time.sleep(30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status", "check", "_run"])
    parser.add_argument("--thread", default=os.environ.get("CODEX_THREAD_ID"))
    args = parser.parse_args()
    os.umask(0o077)
    directory = state_directory()
    if args.action == "status":
        print(json.dumps({"running": running(directory), "schedule": "09:30 Asia/Shanghai",
                          "config": read_json(directory / "config.json"),
                          "lastCheck": read_json(directory / "last-check.json")},
                         ensure_ascii=False, indent=2))
        return
    directory.mkdir(parents=True, exist_ok=True)
    if args.action == "stop":
        config = read_json(directory / "config.json", {})
        config["enabled"] = False
        save_json(directory / "config.json", config)
        print("Disabled; watcher exits within 30 seconds (or after a pending retry).")
    elif args.action == "check":
        print(json.dumps(check(directory, queue=False), ensure_ascii=False, indent=2))
    elif args.action == "_run":
        run(directory)
    else:
        if running(directory):
            config = read_json(directory / "config.json")
            config["enabled"] = True
            if args.thread:
                config["thread"] = args.thread
            save_json(directory / "config.json", config)
            print("Watcher is enabled and already running; no duplicate process started.")
            return
        saved = read_json(directory / "config.json", {})
        thread = args.thread or saved.get("thread")
        if not thread:
            parser.error("start requires --thread or CODEX_THREAD_ID")
        import shutil
        codex = shutil.which("codex")
        if not codex:
            parser.error("codex CLI is required")
        save_json(directory / "config.json", {"enabled": True, "thread": thread,
                                              "codex": codex, "cwd": str(ROOT)})
        # Starting after 09:30 performs today's check immediately, then waits until tomorrow.
        with (directory / "watch.log").open("a") as output:
            subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "_run"],
                             cwd=ROOT, stdin=subprocess.DEVNULL, stdout=output,
                             stderr=output, start_new_session=True, close_fds=True)
        for _ in range(30):
            if running(directory):
                print(f"Started daily 09:30 Asia/Shanghai; state: {directory}")
                return
            time.sleep(0.1)
        raise SystemExit(f"Watcher failed to start; see {directory / 'watch.log'}")


if __name__ == "__main__":
    main()
