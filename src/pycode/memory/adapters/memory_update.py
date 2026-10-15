"""One LangMem Store Manager pass over recent dialogue; LangMem owns extraction and merging."""

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langmem import create_memory_store_manager

INSTRUCTIONS = (
    "Maintain durable long-term memory for a coding assistant. Save lasting user preferences, "
    "explicit corrections of the assistant's behavior and stable project knowledge. Merge "
    "duplicates and update outdated entries instead of adding near-copies. Skip one-off task "
    "requests, temporary state, guesses and anything the user did not state or confirm. "
    "Never save credentials, tokens, passwords or keys. Keep each memory short and factual."
)
# Store Manager searches the store with dilated windows of the dialogue (query_limit // 4).
QUERY_LIMIT = 20


def framework_messages(summary, messages):
    converted = []
    if summary:
        converted.append(SystemMessage("Summary of the earlier conversation:\n" + summary))
    for message in messages:
        factory = HumanMessage if message.role == "user" else AIMessage
        converted.append(factory(message.text, id=message.id))
    return converted


class MemoryConsolidator:
    def __init__(self, model):
        self.model = model

    async def __call__(self, store, namespace, summary, messages):
        manager = create_memory_store_manager(
            self.model,
            instructions=INSTRUCTIONS,
            namespace=tuple(namespace),
            store=store,
            query_limit=QUERY_LIMIT,
        )
        return await manager.ainvoke(
            {"messages": framework_messages(summary, messages), "max_steps": 1}
        )
