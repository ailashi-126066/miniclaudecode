"""A tool receipt binds its original operation and arguments without copying memory bodies."""

from pycode.core.hashing import data_hash


def receipt_id(namespace, context, name):
    return data_hash(
        {
            "namespace": namespace,
            "session_id": context.session_id,
            "run_id": context.run_id,
            "operation_id": context.operation_id,
            "call_id": context.call_id,
            "name": name,
        }
    )


def receipt_metadata(context, name, arguments_sha256, evidence):
    return {
        "session_id": context.session_id,
        "run_id": context.run_id,
        "workspace": context.workspace,
        "operation_id": context.operation_id,
        "call_id": context.call_id,
        "name": name,
        "arguments_sha256": arguments_sha256,
        "source_leaf_id": evidence.leaf_id,
        "source_prefix_hash": evidence.prefix_hash,
    }
