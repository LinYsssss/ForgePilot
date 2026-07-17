import java.sql.*;
class App {
  void load(Connection connection) throws Exception {
    PreparedStatement statement = connection.prepareStatement("select 1");
    ResultSet rows = statement.executeQuery();
    while (rows.next()) System.out.println(rows.getInt(1));
  }
}
