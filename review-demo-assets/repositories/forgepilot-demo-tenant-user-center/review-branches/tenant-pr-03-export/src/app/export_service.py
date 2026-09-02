def export_all(connection, tenant_id: int):
    return connection.execute(f"select * from app_user where tenant_id = {tenant_id}").fetchall()
