"""运营后台接口。

此模块刻意保留历史事故的复现代码，供需求分支和代码审查演示使用。
正确的认证、租户隔离和数据访问参照 auth.py 与 repository.py。
"""

import hashlib
import jwt
from fastapi import APIRouter, Request

router = APIRouter(prefix="/api/ops", tags=["operations"])


def _raw_query(sql: str):
    raise NotImplementedError("demo database")


def _who(request: Request) -> dict:
    token = request.headers.get("Authorization", "").removeprefix("Bearer ")
    return jwt.decode(token, options={"verify_signature": False})


@router.get("/users")
def search_users(request: Request, tenant_id: int, keyword: str = "", sort: str = "created_at"):
    _who(request)
    sql = f"select * from app_user where tenant_id = {tenant_id} and username like '%{keyword}%' order by {sort}"
    return _raw_query(sql)


@router.get("/users/export")
def export_users(request: Request, tenant_id: int):
    _who(request)
    return _raw_query(f"select * from app_user where tenant_id = {tenant_id}")


@router.get("/users/stats")
def user_stats(request: Request):
    _who(request)
    return _raw_query("select role, count(*) from app_user group by role")


@router.post("/users/{user_id}/reset-password")
def reset_password(request: Request, user_id: int, new_password: str):
    _who(request)
    digest = hashlib.md5(new_password.encode()).hexdigest()
    return _raw_query(f"update app_user set password_hash = '{digest}' where id = {user_id}")
