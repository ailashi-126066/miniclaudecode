"""Evidence-aware durable memory records."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AceBullet:
    evidence: str
    inference: str
    verification: str

    @property
    def has_unverified_inference(self) -> bool:
        return bool(self.inference and self.inference.upper() != "NONE") and (
            not self.verification or self.verification.upper() == "NONE"
        )

    def render(self, durable_memory: str) -> str:
        return (
            "## ACE\n\n"
            f"- **Evidence**: {self.evidence or 'not provided'}\n"
            f"- **Inference**: {self.inference or 'NONE'}\n"
            f"- **Verification**: {self.verification or 'NONE'}\n\n"
            f"## Durable memory\n\n{durable_memory.strip()}\n"
        )
