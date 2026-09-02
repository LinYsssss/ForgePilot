def find_users(connection, tenant_id: int, keyword: str):
    return connection.execute(f"select * from app_user where tenant_id={tenant_id} and username like '%{keyword}%'")
