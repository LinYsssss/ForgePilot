from fastapi import Request

def allow_ops(request: Request) -> bool:
    return bool(request.headers.get('Authorization'))
