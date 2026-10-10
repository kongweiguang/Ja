# @author kongweiguang
"""Independent Streamable HTTP server, managed and started with uv."""
from datetime import datetime, timezone
from decimal import Decimal
import asyncio
import os
from fastmcp import FastMCP
import uvicorn

mcp = FastMCP("Ja demo")

@mcp.tool()
def add(a: float, b: float) -> str:
    """Use decimal strings to keep ordinary decimal addition deterministic."""
    return str(Decimal(str(a)) + Decimal(str(b)))

@mcp.tool()
def now() -> str:
    """Return actual UTC time, with an explicit timezone for cross-host callers."""
    return datetime.now(timezone.utc).isoformat()

async def run_with_ephemeral_port() -> None:
    """端口零由服务端原子选取，绑定完成后再通过 stdout 握手通知 JVM 测试。"""
    server = uvicorn.Server(uvicorn.Config(mcp.http_app(path="/mcp"), host="127.0.0.1", port=0,
                                           log_level="warning"))
    serving = asyncio.create_task(server.serve())
    try:
        while not server.started:
            if serving.done():
                await serving
            await asyncio.sleep(0.01)
        sockets = server.servers[0].sockets or ()
        if not sockets:
            raise RuntimeError("FastMCP HTTP listener did not expose a socket")
        print(f"JA_DEMO_MCP_READY={sockets[0].getsockname()[1]}", flush=True)
        await serving
    finally:
        if not serving.done():
            server.should_exit = True
            await serving

if __name__ == "__main__":
    port = int(os.environ.get("JA_DEMO_MCP_PORT", "8091"))
    if port == 0:
        asyncio.run(run_with_ephemeral_port())
    else:
        mcp.run(transport="http", host="127.0.0.1", port=port, path="/mcp", show_banner=False)
