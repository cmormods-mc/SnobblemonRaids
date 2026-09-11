"""Minimal RCON client -- raw sockets, no dependencies.

Kept deliberately dependency-free so the smoke harness can run anywhere the repo is checked out,
without a pip install standing between a developer and a live verification.
"""

import socket
import struct

AUTH = 3
AUTH_RESPONSE = 2
EXEC_COMMAND = 2


class RconError(RuntimeError):
    pass


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 30.0):
        self._host, self._port, self._password, self._timeout = host, port, password, timeout
        self._sock: socket.socket | None = None

    def __enter__(self) -> "Rcon":
        self._sock = socket.create_connection((self._host, self._port), timeout=self._timeout)
        self._send(1, AUTH, self._password)
        request_id, _, _ = self._read()
        if request_id == -1:
            raise RconError("RCON authentication failed -- check rcon.password in server.properties")
        return self

    def __exit__(self, *_exc) -> None:
        if self._sock:
            self._sock.close()
            self._sock = None

    def command(self, line: str) -> str:
        """Run one command as the console and return its (colour-stripped, single-line) output."""
        self._send(2, EXEC_COMMAND, line)
        _, _, payload = self._read()
        return payload

    def _send(self, request_id: int, packet_type: int, payload: str) -> None:
        assert self._sock is not None
        body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
        self._sock.sendall(struct.pack("<i", len(body)) + body)

    def _read(self) -> tuple[int, int, str]:
        assert self._sock is not None
        raw_length = self._recv_exactly(4)
        length = struct.unpack("<i", raw_length)[0]
        data = self._recv_exactly(length)
        request_id, packet_type = struct.unpack("<ii", data[:8])
        return request_id, packet_type, data[8:-2].decode("utf-8", errors="replace")

    def _recv_exactly(self, count: int) -> bytes:
        assert self._sock is not None
        chunks = b""
        while len(chunks) < count:
            block = self._sock.recv(count - len(chunks))
            if not block:
                raise RconError("RCON connection closed mid-packet -- did the server stop?")
            chunks += block
        return chunks
