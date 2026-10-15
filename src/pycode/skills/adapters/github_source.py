# Source: MewCode local skills/install.py; SHA-256:
# 0393811fad87caf067df6c006cc652298ebb151c86ee9883087d557bc85178b0
# Adaptation: repository roots, explicit ref/path overrides and portable path validation.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Validate GitHub Skill sources before network or filesystem access."""

import re
from dataclasses import dataclass
from urllib.parse import unquote, urlsplit

from pycode.kernel import ToolError


@dataclass(frozen=True)
class GitHubSkillSource:
    owner: str
    repo: str
    ref: str | None
    subpath: str
    directory_name: str
    original: str


def safe_component(value):
    if (
        not isinstance(value, str)
        or not value
        or value in {".", ".."}
        or any(ord(char) < 32 or char in '/\\:*?"<>|' for char in value)
        or value.endswith((".", " "))
        or value.split(".")[0].upper()
        in {
            "CON",
            "PRN",
            "AUX",
            "NUL",
            *[f"COM{index}" for index in range(1, 10)],
            *[f"LPT{index}" for index in range(1, 10)],
        }
    ):
        raise ToolError("invalid_skill_source", "Skill source contains an unsafe path component")
    return value


def safe_subpath(value):
    if not isinstance(value, str) or value.startswith("/") or value.endswith("/"):
        raise ToolError("invalid_skill_source", "Skill source subpath must be relative")
    if not value:
        return ""
    return "/".join(safe_component(part) for part in value.split("/"))


def parse_skill_url(raw, *, ref=None, subpath=None):
    try:
        url = urlsplit(raw.strip())
        if (
            url.scheme != "https"
            or url.username
            or url.password
            or url.port not in {None, 443}
            or url.query
            or url.fragment
        ):
            raise ValueError("Use HTTPS without credentials, query or fragment")
        parts = [safe_component(unquote(part)) for part in url.path.strip("/").split("/")]
        if len(parts) < 2:
            raise ValueError("Missing GitHub owner/repository")
        owner, repo = parts[:2]
        if not all(re.fullmatch(r"[A-Za-z0-9_.-]+", item) for item in (owner, repo)):
            raise ValueError("Invalid GitHub repository")
        repo = repo.removesuffix(".git")
        safe_component(repo)
        source_ref, source_path = None, ""
        if url.hostname in {"skills.sh", "www.skills.sh"}:
            if len(parts) < 3:
                raise ValueError("skills.sh requires a Skill name")
            source_ref, source_path = "main", "skills/" + "/".join(parts[2:])
        elif url.hostname == "github.com":
            if len(parts) != 2:
                if len(parts) < 4 or parts[2] != "tree":
                    raise ValueError("Use a GitHub repository or tree URL")
                source_ref, source_path = parts[3], "/".join(parts[4:])
        elif url.hostname == "raw.githubusercontent.com":
            if len(parts) < 4 or parts[-1] != "SKILL.md":
                raise ValueError("Raw URL must identify SKILL.md")
            source_ref, source_path = parts[2], "/".join(parts[3:-1])
        else:
            raise ValueError("Use GitHub, raw.githubusercontent.com or skills.sh")
        selected_ref = ref if ref is not None else source_ref
        if selected_ref is not None:
            if not isinstance(selected_ref, str) or not selected_ref or len(selected_ref) > 255:
                raise ValueError("Invalid Git ref")
            safe_subpath(selected_ref)
        selected_path = safe_subpath(subpath if subpath is not None else source_path)
        directory_name = selected_path.split("/")[-1] if selected_path else repo
        if directory_name.startswith("."):
            raise ValueError("Skill destination must be visible to the catalog")
        return GitHubSkillSource(
            owner, repo, selected_ref, selected_path, directory_name, raw.strip()
        )
    except (ValueError, AttributeError) as error:
        raise ToolError("invalid_skill_source", "Cannot parse GitHub Skill URL") from error
