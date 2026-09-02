import hashlib

def reset_password(connection, user_id: int, password: str):
    digest = hashlib.sha256(password.encode()).hexdigest()
    return connection.execute('update app_user set password_hash=%s where id=%s', (digest, user_id))
