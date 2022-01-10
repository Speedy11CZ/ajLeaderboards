package us.ajg0702.leaderboards.cache.methods;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import us.ajg0702.leaderboards.Debug;
import us.ajg0702.leaderboards.LeaderboardPlugin;
import us.ajg0702.leaderboards.boards.TimedType;
import us.ajg0702.leaderboards.cache.Cache;
import us.ajg0702.leaderboards.cache.CacheMethod;
import us.ajg0702.utils.common.ConfigFile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

public class MysqlMethod implements CacheMethod {
    @Override
    public Connection getConnection() throws SQLException {
        if(ds == null) return null;
        return ds.getConnection();
    }

    private final HikariConfig hikariConfig = new HikariConfig();
    private HikariDataSource ds;


    @Override
    public void init(LeaderboardPlugin plugin, ConfigFile config, Cache cacheInstance) {
        String ip = config.getString("ip");
        String username = config.getString("username");
        String password = config.getString("password");
        String database = config.getString("database");
        boolean useSSL = config.getBoolean("useSSL");
        boolean allowPublicKeyRetrieval = config.getBoolean("allowPublicKeyRetrieval");
        int minCount = config.getInt("minConnections");
        int maxCount = config.getInt("maxConnections");

        String url = "jdbc:mysql://"+ip+"/"+database+"?useSSL="+useSSL+"&allowPublicKeyRetrieval="+allowPublicKeyRetrieval+"&characterEncoding=utf8&useInformationSchema=true";
        hikariConfig.setDriverClassName("com.mysql.jdbc.Driver");
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxCount);
        hikariConfig.setMinimumIdle(minCount);
        //hikariConfig.setRegisterMbeans(true);
        ds = new HikariDataSource(hikariConfig);
        ds.setLeakDetectionThreshold(60 * 1000);

        List<String> tables = cacheInstance.getDbTableList();

        try(Connection conn = getConnection()) {
            //ResultSet rs = conn.getMetaData().getTables(null, null, "", null);
            Statement statement = conn.createStatement();
            for(String tableName : tables) {
                int version;
                if(!tableName.startsWith(cacheInstance.getTablePrefix())) continue;
                try {
                    ResultSet rs = conn.createStatement().executeQuery("show table status where Name='"+tableName+"'");
                    rs.next();
                    version = Integer.parseInt(rs.getString("COMMENT"));
                    rs.close();
                } catch(NumberFormatException e) {
                    version = 0;
                } catch(SQLException e) {
                    if(e.getMessage().contains("Column 'COMMENT' not found")) {
                        version = 0;
                    } else {
                        throw e;
                    }
                }
                Debug.info("Table version for "+tableName+" is: "+version);

                if(version == 0) {
                    plugin.getLogger().info("Running MySQL table updater for table "+tableName+" (pv"+version+")");

                    for(TimedType typeEnum : TimedType.values()) {
                        if(typeEnum == TimedType.ALLTIME) continue;
                        String type = typeEnum.name().toLowerCase(Locale.ROOT);
                        try {
                            statement.executeUpdate("alter table "+tableName+" add column "+type+"_delta BIGINT");
                            statement.executeUpdate("alter table "+tableName+" add column "+type+"_lasttotal BIGINT");
                            statement.executeUpdate("alter table "+tableName+" add column "+type+"_timestamp TIMESTAMP");
                        } catch(SQLException e) {
                            if(e.getMessage().contains("Duplicate")) {
                                plugin.getLogger().info("The columns already exist for "+tableName+". Canceling updater and bumping DB version.");
                                try {
                                    conn.createStatement().executeUpdate("ALTER TABLE "+tableName+" COMMENT = '1';");
                                } catch (SQLException er) {
                                    er.printStackTrace();
                                    throw e;
                                }
                            } else {
                                throw e;
                            }
                        }
                    }

                    statement.executeUpdate("ALTER TABLE "+tableName+" COMMENT = '1';");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public void shutdown() {
        ds.close();
    }
}
