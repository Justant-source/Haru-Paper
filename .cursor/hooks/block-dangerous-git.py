#!/usr/bin/env python3
"""Block the same destructive git family as .claude/settings.local.json (Cursor)."""

from __future__ import annotations

import json
import re
import shlex
import sys

DENY_SUBCOMMANDS = frozenset(
    {"merge", "rebase", "reset", "revert", "tag"}
)


def extract_command(payload: dict) -> str:
    if isinstance(payload.get("command"), str):
        return payload["command"]
    tool_input = payload.get("tool_input")
    if isinstance(tool_input, dict) and isinstance(tool_input.get("command"), str):
        return tool_input["command"]
    return ""


def git_argv(command: str) -> list[str] | None:
    try:
        parts = shlex.split(command)
    except ValueError:
        parts = command.split()
    for i, part in enumerate(parts):
        name = part.rsplit("/", 1)[-1]
        if name == "git":
            return parts[i:]
    return None


def denied_reason(git_args: list[str]) -> str | None:
    args = git_args[1:]
    while args and args[0].startswith("-") and args[0] not in {"-C", "-c"}:
        # git --no-pager reset, git -C dir merge, etc.
        if args[0] in {"--version", "--help"}:
            return None
        args = args[1:]
    while args:
        if args[0] == "-C" and len(args) >= 2:
            args = args[2:]
            continue
        if args[0] == "-c" and len(args) >= 2:
            args = args[2:]
            continue
        if args[0].startswith("-"):
            args = args[1:]
            continue
        break
    if not args:
        return None
    sub = args[0]
    rest = args[1:]
    if sub in DENY_SUBCOMMANDS:
        return f"git {sub}* is denied (CLAUDE.md / .cursor/cli.json). Stop and report; do not merge/rebase/reset yourself."
    if sub == "stash" and rest and rest[0] == "drop":
        return "git stash drop* is denied."
    if sub == "branch" and any(a in {"-d", "-D"} or a.startswith("-d") or a.startswith("-D") for a in rest):
        return "git branch -d/-D is denied."
    if sub == "checkout" and any(a == "-B" or a.startswith("-B") for a in rest):
        return "git checkout -B is denied."
    if sub == "push":
        joined = " ".join(rest)
        if re.search(r"(^|\s)(-f|--force|--force-with-lease|--force-if-includes)(\s|=|$)", joined):
            return "git push --force is denied."
    return None


def deny(command: str, reason: str) -> dict:
    return {
        "permission": "deny",
        "user_message": f"Blocked git command: {command}",
        "agent_message": reason,
    }


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        print(json.dumps({"permission": "deny", "agent_message": "git hook: invalid JSON stdin"}))
        sys.exit(0)
    command = extract_command(payload if isinstance(payload, dict) else {})
    git_args = git_argv(command)
    if not git_args:
        print(json.dumps({"permission": "allow"}))
        return
    reason = denied_reason(git_args)
    if reason:
        print(json.dumps(deny(command, reason), ensure_ascii=False))
        return
    print(json.dumps({"permission": "allow"}))


if __name__ == "__main__":
    main()
