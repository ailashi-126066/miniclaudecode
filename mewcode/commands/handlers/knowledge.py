from __future__ import annotations

from mewcode.commands.registry import Command, CommandContext, CommandType
from mewcode.config import KnowledgeConfig
from mewcode.rag import (
    KnowledgeEmbeddingError,
    KnowledgeIndexCompatibilityError,
    KnowledgeIndexNotFoundError,
    KnowledgeRagService,
)


async def handle_knowledge(ctx: CommandContext) -> None:
    knowledge_config = ctx.config.get("knowledge_config")
    if not isinstance(knowledge_config, KnowledgeConfig) or not knowledge_config.enabled or knowledge_config.embedding is None:
        ctx.ui.add_system_message(
            "Knowledge retrieval is disabled. Configure knowledge.enabled and knowledge.embedding first."
        )
        return
    service = KnowledgeRagService(
        ctx.agent.work_dir if ctx.agent else ".", knowledge_config.embedding
    )
    args = ctx.args.strip()
    action, _, remaining = args.partition(" ")
    action = action.casefold() if action else "status"
    try:
        if action in {"index", "sync"}:
            message = f"Knowledge index synchronized: {await service.synchronize()}"
        elif action == "status":
            status = service.status()
            if not status.indexed:
                message = f"Knowledge index missing. Add documents to {status.knowledge_root} and run /knowledge index."
            else:
                reason = f"\nReason: {status.reason}" if status.reason else ""
                message = (f"Knowledge index: {'STALE' if status.stale else 'READY'}\n"
                           f"Documents: {status.documents}\nChunks: {status.chunks}\nSource: {status.knowledge_root}")
                message += reason
        else:
            status = service.status()
            if not status.indexed:
                message = f"Knowledge index missing. Add documents to {status.knowledge_root} and run /knowledge index."
            elif status.stale:
                message = "Knowledge index is stale. Run /knowledge index before searching."
            else:
                response = await service.search(args)
                if not response.results:
                    message = "No relevant knowledge found."
                else:
                    message = "Knowledge sources:\n" + "\n".join(
                        f"\n- {result.path}:{result.start_line}-{result.end_line} {result.heading}\n{result.content}"
                        for result in response.results
                    )
    except (
        KnowledgeEmbeddingError,
        KnowledgeIndexCompatibilityError,
        KnowledgeIndexNotFoundError,
        ValueError,
    ) as error:
        message = f"Knowledge command failed: {error}"
    ctx.ui.add_system_message(message)


KNOWLEDGE_COMMAND = Command(
    name="knowledge",
    aliases=["kb"],
    description="Index, inspect, or search private knowledge documents",
    usage="/knowledge <index|status|query>",
    type=CommandType.LOCAL,
    handler=handle_knowledge,
)
