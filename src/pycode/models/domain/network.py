"""Pure host classification shared by configuration and local service policy."""

import ipaddress


def is_loopback_host(hostname):
    """Return whether a URL hostname names this machine (127.0.0.0/8, ::1, localhost)."""
    if not hostname:
        return False
    hostname = hostname.strip("[]").rstrip(".").casefold()
    if hostname == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False
