"""租户作用域数据访问。

所有查询强制注入 tenant_id，参见 docs/tenant-isolation.md 第 3 节。
业务代码不得绕过本层直接拼 SQL。
"""

from dataclasses import dataclass
from typing import Any, Sequence

MAX_EXPORT_ROWS = 10_000

# 排序字段白名单，防止把客户端输入拼进 ORDER BY
SORTABLE_COLUMNS = frozenset({"id", "username", "created_at", "last_login_at"})


@dataclass(frozen=True)
class TenantContext:
    """来自认证上下文的租户信息。绝不从请求参数构造。"""

    tenant_id: int
    user_id: int
    role: str


class TenantScopedRepository:
    def __init__(self, connection: Any) -> None:
        self._conn = connection

    def list_users(
        self,
        ctx: TenantContext,
        page: int = 0,
        size: int = 20,
        sort: str = "created_at",
    ) -> Sequence[dict]:
        if sort not in SORTABLE_COLUMNS:
            raise ValueError(f"unsupported sort column: {sort}")
        size = min(max(size, 1), 100)
        page = max(page, 0)

        sql = (
            "select id, username, nickname, role, created_at "
            "from app_user "
            "where tenant_id = %s and deleted_at is null "
            f"order by {sort} desc "
            "limit %s offset %s"
        )
        return self._query(sql, (ctx.tenant_id, size, page * size))

    def find_user(self, ctx: TenantContext, user_id: int) -> dict | None:
        sql = (
            "select id, username, nickname, role, email, phone "
            "from app_user where tenant_id = %s and id = %s and deleted_at is null"
        )
        rows = self._query(sql, (ctx.tenant_id, user_id))
        return rows[0] if rows else None

    def count_users(self, ctx: TenantContext) -> int:
        sql = "select count(*) as c from app_user where tenant_id = %s and deleted_at is null"
        return self._query(sql, (ctx.tenant_id,))[0]["c"]

    def export_users(self, ctx: TenantContext, limit: int) -> Sequence[dict]:
        limit = min(limit, MAX_EXPORT_ROWS)
        sql = (
            "select id, username, nickname, role, created_at "
            "from app_user where tenant_id = %s and deleted_at is null limit %s"
        )
        return self._query(sql, (ctx.tenant_id, limit))

    def _query(self, sql: str, params: tuple) -> list[dict]:
        raise NotImplementedError("demo repository")
