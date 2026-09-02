"""用户中心 HTTP 入口。"""

from fastapi import Depends, FastAPI, HTTPException, Request

from .auth import TokenClaims, verify_token
from .repository import TenantContext, TenantScopedRepository
from .ops_console import router as ops_router

app = FastAPI(title="tenant-user-center")
app.include_router(ops_router)


def current_context(request: Request) -> TenantContext:
    """租户来自认证上下文，不从请求参数取。见 tenant-isolation.md 第 2 节第 3 条。"""
    token = request.cookies.get("uc_auth")
    if not token:
        raise HTTPException(status_code=401, detail="未登录")
    claims: TokenClaims = verify_token(token)
    return TenantContext(tenant_id=claims.tenant_id, user_id=claims.user_id, role=claims.role)


def repository() -> TenantScopedRepository:
    raise NotImplementedError("demo wiring")


@app.get("/api/users")
def list_users(
    page: int = 0,
    size: int = 20,
    sort: str = "created_at",
    ctx: TenantContext = Depends(current_context),
    repo: TenantScopedRepository = Depends(repository),
):
    items = repo.list_users(ctx, page=page, size=size, sort=sort)
    return {"code": 0, "errorCode": "OK", "data": {"items": items, "page": page, "size": size}}


@app.get("/api/users/{user_id}")
def get_user(
    user_id: int,
    ctx: TenantContext = Depends(current_context),
    repo: TenantScopedRepository = Depends(repository),
):
    user = repo.find_user(ctx, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    return {"code": 0, "errorCode": "OK", "data": user}
