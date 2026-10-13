"""Observable request storage and deterministic clock replacement for tests."""

from copy import deepcopy


class MemoryRequestStore:
    def __init__(self):
        self.records = {}

    async def start(self, record):
        self.records[record.request_id] = deepcopy(record)

    async def settle(self, record):
        self.records[record.request_id] = deepcopy(record)

    async def list_requests(self, session_id=None):
        return tuple(
            record
            for record in self.records.values()
            if session_id is None or record.session_id == session_id
        )


class TestTiming:
    __test__ = False

    def __init__(self):
        self.current = 1000.0
        self.sleeps = []

    def now(self):
        return self.current

    async def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.current += seconds
