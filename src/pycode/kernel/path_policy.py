"""Pure reserved-component policy shared by workspace adapters."""


def is_internal_component(part):
    # State directory names are reserved across platforms, including aliases
    # on the common case-insensitive macOS and Windows filesystems.
    return part.casefold() in {".git", ".pycode"}
