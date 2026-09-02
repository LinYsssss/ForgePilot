"""认证与密码。

密码用 bcrypt cost=12；令牌校验签名与过期。参见 docs/auth-policy.md。
"""

import os
import time
from dataclasses import dataclass

import bcrypt
import jwt

BCRYPT_COST = 12
TOKEN_TTL_SECONDS = 24 * 3600


def _signing_key() -> str:
    key = os.environ.get("TOKEN_SIGNING_KEY")
    if not key:
        raise RuntimeError("TOKEN_SIGNING_KEY 未配置")
    return key


def hash_password(raw: str) -> str:
    if len(raw) < 12:
        raise ValueError("密码至少 12 位")
    return bcrypt.hashpw(raw.encode(), bcrypt.gensalt(rounds=BCRYPT_COST)).decode()


def verify_password(raw: str, stored: str) -> bool:
    return bcrypt.checkpw(raw.encode(), stored.encode())


@dataclass(frozen=True)
class TokenClaims:
    tenant_id: int
    user_id: int
    role: str
    session_version: int


def issue_token(claims: TokenClaims) -> str:
    now = int(time.time())
    payload = {
        "tid": claims.tenant_id,
        "uid": claims.user_id,
        "role": claims.role,
        "sv": claims.session_version,
        "iat": now,
        "exp": now + TOKEN_TTL_SECONDS,
    }
    return jwt.encode(payload, _signing_key(), algorithm="HS256")


def verify_token(token: str) -> TokenClaims:
    # 显式校验签名与过期时间；只 decode 不 verify 等于没有认证
    payload = jwt.decode(
        token,
        _signing_key(),
        algorithms=["HS256"],
        options={"verify_signature": True, "verify_exp": True, "require": ["exp", "iat"]},
    )
    return TokenClaims(
        tenant_id=payload["tid"],
        user_id=payload["uid"],
        role=payload["role"],
        session_version=payload["sv"],
    )
