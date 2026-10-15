"""Assemble scoped tool-output storage from explicit feature products."""


def build_outputs(modules, session_id, history):
    return modules.tools.output_store(
        modules.sessions.layout.sessions,
        session_id,
        history,
        header_reader=modules.sessions.header_reader(),
    )
