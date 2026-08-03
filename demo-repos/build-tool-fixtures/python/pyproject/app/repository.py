def find_user(connection, user_input):
    return connection.execute("select * from users where name = '" + user_input + "'")
