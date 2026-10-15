"""Immutable stored values and explicit conversion at serialization boundaries."""

import copy
import json
from collections.abc import Mapping
from dataclasses import Field, fields, is_dataclass, replace
from types import MappingProxyType
from typing import Any, ClassVar, Protocol, TypeVar, overload


class _DataclassValue(Protocol):
    __dataclass_fields__: ClassVar[dict[str, Field[Any]]]


_Record = TypeVar("_Record", bound=_DataclassValue)
_Key = TypeVar("_Key")


@overload
def plain_data(value: _DataclassValue) -> dict[str, Any]: ...


@overload
def plain_data(value: Mapping[_Key, Any]) -> dict[_Key, Any]: ...


@overload
def plain_data(value: tuple | list) -> list[Any]: ...


@overload
def plain_data(value: Any) -> Any: ...


def plain_data(value: Any) -> Any:
    if is_dataclass(value) and not isinstance(value, type):
        result = {}
        for field in fields(value):
            if field.metadata.get("serialize", True):
                result[field.name] = plain_data(getattr(value, field.name))
        return result
    if isinstance(value, Mapping):
        result = {}
        for key, item in value.items():
            result[key] = plain_data(item)
        return result
    if isinstance(value, (tuple, list)):
        result = []
        for item in value:
            result.append(plain_data(item))
        return result
    return value


@overload
def freeze_value(value: _Record) -> _Record: ...


@overload
def freeze_value(value: Mapping[_Key, Any]) -> Mapping[_Key, Any]: ...


@overload
def freeze_value(value: tuple | list) -> tuple[Any, ...]: ...


@overload
def freeze_value(value: Any) -> Any: ...


def freeze_value(value: Any) -> Any:
    if isinstance(value, MappingProxyType):
        return value
    if is_dataclass(value) and not isinstance(value, type):
        changes = {}
        for field in fields(value):
            if field.init:
                current = getattr(value, field.name)
                frozen = freeze_value(current)
                if frozen is not current:
                    changes[field.name] = frozen
        if not changes:
            return value
        return replace(value, **changes)
    if isinstance(value, Mapping):
        values = {}
        for key, item in value.items():
            values[key] = freeze_value(item)
        return MappingProxyType(values)
    if isinstance(value, (tuple, list)):
        values = []
        unchanged = isinstance(value, tuple)
        for item in value:
            frozen = freeze_value(item)
            if frozen is not item:
                unchanged = False
            values.append(frozen)
        if unchanged:
            return value
        return tuple(values)
    return value


@overload
def clone_value(value: _Record) -> _Record: ...


@overload
def clone_value(value: Mapping[_Key, Any]) -> dict[_Key, Any]: ...


@overload
def clone_value(value: tuple) -> tuple[Any, ...]: ...


@overload
def clone_value(value: list) -> list[Any]: ...


@overload
def clone_value(value: Any) -> Any: ...


def clone_value(value: Any) -> Any:
    """Only a value crossing a mutable framework/I/O boundary needs a copy."""
    if is_dataclass(value) and not isinstance(value, type):
        changes = {}
        for field in fields(value):
            if field.init:
                changes[field.name] = clone_value(getattr(value, field.name))
        return replace(value, **changes)
    if isinstance(value, Mapping):
        return plain_data(value)
    if isinstance(value, tuple):
        values = []
        for item in value:
            values.append(clone_value(item))
        return tuple(values)
    if isinstance(value, list):
        values = []
        for item in value:
            values.append(clone_value(item))
        return values
    return copy.deepcopy(value)


def json_dumps(value, **options):
    return json.dumps(plain_data(value), **options)
