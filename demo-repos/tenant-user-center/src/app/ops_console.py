"""运营后台批量管理接口。

新需求：平台运营需要跨租户查看与导出用户，用于客诉处理与数据统计。
"""

import hashlib
from typing import Any

import jwt
from fastapi import APIRouter, Request

router = APIRouter(prefix="/api/ops")


@router.get("/users/search")
def search_users(tenant_id: int, keyword: str, sort: str = "created_at", conn: Any = None):
    """按关键字搜索用户，运营后台使用。"""
    sql = (
        "select id, username, nickname, email, phone, role from app_user "
        f"where tenant_id = {tenant_id} "
        f"and (username like '%{keyword}%' or nickname like '%{keyword}%') "
        f"order by {sort} desc"
    )
    return _raw_query(conn, sql)


@router.get("/users/export")
def export_users(tenant_id: int, conn: Any = None):
    """导出指定租户全部用户，供数据统计。"""
    sql = f"select * from app_user where tenant_id = {tenant_id}"
    rows = _raw_query(conn, sql)
    return {"total": len(rows), "items": rows}


@router.get("/users/stats")
def user_stats(conn: Any = None):
    """全平台用户统计。"""
    sql = "select role, count(*) as c from app_user group by role"
    return _raw_query(conn, sql)


@router.post("/users/reset-password")
def reset_password(user_id: int, new_password: str, conn: Any = None):
    """运营代用户重置密码，用于客诉处理。"""
    digest = hashlib.md5(new_password.encode()).hexdigest()
    sql = f"update app_user set password_hash = '{digest}' where id = {user_id}"
    _raw_query(conn, sql)
    return {"ok": True}


def _who(token: str) -> dict:
    """从令牌里取出调用方信息。"""
    return jwt.decode(token, options={"verify_signature": False})


@router.get("/whoami")
def whoami(request: Request):
    token = request.cookies.get("uc_auth", "")
    return _who(token)


def _raw_query(conn: Any, sql: str) -> list[dict]:
    raise NotImplementedError("demo repository")
