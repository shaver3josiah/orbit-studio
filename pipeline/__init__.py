from __future__ import annotations

import subprocess
from typing import Callable, Optional


class CancelledError(Exception):
    pass


class ProcessHolder:
    def __init__(self) -> None:
        self.process: Optional[subprocess.Popen] = None
        self.cancelled = False

    def request_cancel(self) -> bool:
        self.cancelled = True
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            return True
        return False


def run_cancelable(
    cmd: list[str],
    holder: ProcessHolder,
    on_line: Optional[Callable[[str], None]] = None,
    timeout: Optional[float] = None,
) -> subprocess.CompletedProcess:
    if holder.cancelled:
        raise CancelledError()
    process = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    holder.process = process
    lines: list[str] = []
    try:
        if process.stdout is not None:
            for line in process.stdout:
                lines.append(line)
                if on_line is not None:
                    on_line(line)
                if holder.cancelled:
                    process.terminate()
        returncode = process.wait(timeout=timeout)
    finally:
        holder.process = None
    if holder.cancelled:
        raise CancelledError()
    return subprocess.CompletedProcess(cmd, returncode, "".join(lines), "")


class RunContext:
    def __init__(self, progress: Callable[[int, str], None], holder: Optional[ProcessHolder] = None) -> None:
        self.progress = progress
        self.holder = holder if holder is not None else ProcessHolder()

    def check_cancelled(self) -> None:
        if self.holder.cancelled:
            raise CancelledError()

    def report(self, pct: int, line: str) -> None:
        self.check_cancelled()
        self.progress(pct, line)

    def run(
        self,
        cmd: list[str],
        on_line: Optional[Callable[[str], None]] = None,
        timeout: Optional[float] = None,
    ) -> subprocess.CompletedProcess:
        return run_cancelable(cmd, self.holder, on_line=on_line, timeout=timeout)
