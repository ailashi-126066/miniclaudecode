# Source: MewCode local permissions/dangerous.py; SHA-256:
# 6de120e749f1aaa3ea12fe657891c0b2bf944d26e5de33bdefecbd0bf6443d95
# Adaptation: pure source deny patterns; no claimed general shell sandbox.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Source command deny rules remain ahead of sandbox automatic approval."""

import re

PATTERNS = (
    (r"rm\s+-[a-z]*r[a-z]*f[a-z]*\s+/\s*$", "Recursive forced deletion of root"),
    (r"mkfs\.", "Disk formatting"),
    (r"dd\s+if=.*of=/dev/", "Direct disk device write"),
    (r"chmod\s+-R\s+777\s+/", "Recursive root permission change"),
    (r":\(\)\{\s*:\|:&\s*\};:", "Fork bomb"),
    (r"curl\s+.*\|\s*(ba)?sh", "Remote script execution through a pipe"),
    (r"wget\s+.*\|\s*(ba)?sh", "Remote script execution through a pipe"),
    (r">\s*/dev/sd", "Disk device overwrite"),
)


def denied_command(command):
    for pattern, reason in PATTERNS:
        if re.search(pattern, command):
            return reason
    return None
