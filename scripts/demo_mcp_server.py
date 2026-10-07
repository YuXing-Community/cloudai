"""CloudAI 集成测试用的本地 MCP Server + 简单 HTTP 接口（端口 8599）"""
from mcp.server.fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import JSONResponse

mcp = FastMCP("cloudai-demo", host="127.0.0.1", port=8599)


@mcp.tool()
def get_task_status(task_id: str) -> str:
    """查询离线任务的运行状态（演示数据）"""
    return f"任务 {task_id}：状态=FAILED，错误=signal 15，运行时长=8h16m，责任人=eyu02"


@mcp.tool()
def add(a: int, b: int) -> int:
    """两数相加"""
    return a + b


@mcp.custom_route("/api/v1/orders/{oid}", methods=["GET"])
async def order(request: Request):
    return JSONResponse({"id": request.path_params["oid"], "status": "paid", "amount": 128.5,
                         "query": dict(request.query_params), "auth": request.headers.get("authorization")})


@mcp.custom_route("/openapi.json", methods=["GET"])
async def spec(request: Request):
    return JSONResponse({
        "openapi": "3.0.0", "info": {"title": "demo", "version": "1"},
        "paths": {"/orders/{oid}": {"get": {"operationId": "getOrder", "summary": "查询订单详情",
                  "parameters": [{"name": "oid", "in": "path", "required": True, "schema": {"type": "string"}},
                                 {"name": "verbose", "in": "query", "schema": {"type": "boolean"}}]}}},
    })


if __name__ == "__main__":
    mcp.run(transport="streamable-http")
