"""Pure credential-like text patterns used by error redaction and memory filtering."""

SECRET_PATTERNS = (
    r"\bsk-[A-Za-z0-9_-]{16,}\b",
    r"(?i)(api[_ -]?key|password|secret|access[_ -]?token)\s*[:=]\s*\S{6,}",
    r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b",
)
