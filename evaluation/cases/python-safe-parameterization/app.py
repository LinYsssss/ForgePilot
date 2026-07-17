def find_user(connection, user_id):
    return connection.execute("select * from users where id = ?", (user_id,)).fetchone()
