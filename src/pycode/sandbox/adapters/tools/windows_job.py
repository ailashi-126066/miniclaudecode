"""Owned Windows job handles; this is process cleanup, not an OS command sandbox."""

import ctypes
from ctypes import wintypes
from typing import Protocol, cast


class BasicLimits(ctypes.Structure):
    _fields_ = [
        ("process_time", ctypes.c_longlong),
        ("job_time", ctypes.c_longlong),
        ("flags", wintypes.DWORD),
        ("minimum_working_set", ctypes.c_size_t),
        ("maximum_working_set", ctypes.c_size_t),
        ("active_processes", wintypes.DWORD),
        ("affinity", ctypes.c_size_t),
        ("priority", wintypes.DWORD),
        ("scheduling", wintypes.DWORD),
    ]


class IOCounters(ctypes.Structure):
    _fields_ = [
        ("read_operations", ctypes.c_ulonglong),
        ("write_operations", ctypes.c_ulonglong),
        ("other_operations", ctypes.c_ulonglong),
        ("read_bytes", ctypes.c_ulonglong),
        ("write_bytes", ctypes.c_ulonglong),
        ("other_bytes", ctypes.c_ulonglong),
    ]


class ExtendedLimits(ctypes.Structure):
    _fields_ = [
        ("basic", BasicLimits),
        ("io", IOCounters),
        ("process_memory", ctypes.c_size_t),
        ("job_memory", ctypes.c_size_t),
        ("peak_process_memory", ctypes.c_size_t),
        ("peak_job_memory", ctypes.c_size_t),
    ]


class ThreadEntry(ctypes.Structure):
    _fields_ = [
        ("size", wintypes.DWORD),
        ("usage", wintypes.DWORD),
        ("thread_id", wintypes.DWORD),
        ("owner_pid", wintypes.DWORD),
        ("priority", wintypes.LONG),
        ("delta_priority", wintypes.LONG),
        ("flags", wintypes.DWORD),
    ]


class WindowsFunction(Protocol):
    argtypes: list[object]
    restype: object

    def __call__(self, *arguments: object) -> int | None: ...


class Kernel32(Protocol):
    CreateJobObjectW: WindowsFunction
    SetInformationJobObject: WindowsFunction
    OpenProcess: WindowsFunction
    AssignProcessToJobObject: WindowsFunction
    CloseHandle: WindowsFunction
    CreateToolhelp32Snapshot: WindowsFunction
    Thread32First: WindowsFunction
    Thread32Next: WindowsFunction
    OpenThread: WindowsFunction
    ResumeThread: WindowsFunction


class WindowsCtypes(Protocol):
    # ctypes exposes these functions only on Windows; this module also imports on POSIX.
    def WinDLL(self, name: str, *, use_last_error: bool) -> Kernel32: ...
    def WinError(self, code: int) -> OSError: ...
    def get_last_error(self) -> int: ...


WINDOWS_CTYPES = cast(WindowsCtypes, ctypes)


class WindowsJob:
    def __init__(self) -> None:
        self._kernel = WINDOWS_CTYPES.WinDLL("kernel32", use_last_error=True)
        self._kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self._kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self._kernel.SetInformationJobObject.argtypes = [
            wintypes.HANDLE,
            ctypes.c_int,
            ctypes.c_void_p,
            wintypes.DWORD,
        ]
        self._kernel.SetInformationJobObject.restype = wintypes.BOOL
        self._kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self._kernel.OpenProcess.restype = wintypes.HANDLE
        self._kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self._kernel.AssignProcessToJobObject.restype = wintypes.BOOL
        self._kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self._kernel.CloseHandle.restype = wintypes.BOOL
        self._kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        self._kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        self._kernel.Thread32First.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        self._kernel.Thread32First.restype = wintypes.BOOL
        self._kernel.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        self._kernel.Thread32Next.restype = wintypes.BOOL
        self._kernel.OpenThread.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self._kernel.OpenThread.restype = wintypes.HANDLE
        self._kernel.ResumeThread.argtypes = [wintypes.HANDLE]
        self._kernel.ResumeThread.restype = wintypes.DWORD
        self._handle = self._kernel.CreateJobObjectW(None, None)
        if not self._handle:
            raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self._kernel.SetInformationJobObject(
            self._handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)
        ):
            self.close()
            raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())

    def attach_and_resume(self, pid: int) -> None:
        process = self._kernel.OpenProcess(0x0100 | 0x0001, False, pid)
        if not process:
            raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())
        try:
            if not self._kernel.AssignProcessToJobObject(self._handle, process):
                raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())
        finally:
            self._kernel.CloseHandle(process)
        snapshot = self._kernel.CreateToolhelp32Snapshot(0x00000004, 0)
        if snapshot == ctypes.c_void_p(-1).value:
            raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())
        resumed = False
        try:
            entry = ThreadEntry()
            entry.size = ctypes.sizeof(entry)
            found = self._kernel.Thread32First(snapshot, ctypes.byref(entry))
            while found:
                if entry.owner_pid == pid:
                    thread = self._kernel.OpenThread(0x0002, False, entry.thread_id)
                    if not thread:
                        raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())
                    try:
                        if self._kernel.ResumeThread(thread) == 0xFFFFFFFF:
                            raise WINDOWS_CTYPES.WinError(WINDOWS_CTYPES.get_last_error())
                        resumed = True
                    finally:
                        self._kernel.CloseHandle(thread)
                found = self._kernel.Thread32Next(snapshot, ctypes.byref(entry))
        finally:
            self._kernel.CloseHandle(snapshot)
        if not resumed:
            raise OSError("Suspended command thread could not be resumed")

    def close(self) -> None:
        if self._handle:
            self._kernel.CloseHandle(self._handle)
            self._handle = None
