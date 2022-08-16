/*
 *    Copyright 2009-2022 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.datasource.pooled;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {

  private static final Log log = LogFactory.getLog(PooledDataSource.class);

  /**
   * PoolState 对象，记录池化的状态
   */
  private final PoolState state = new PoolState(this);

  /**
   * UnpooledDataSource 对象
   */
  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  /**
   * 在任意时间可以存在的活动（也就是正在使用）连接数量
   */
  protected int poolMaximumActiveConnections = 10;
  /**
   * 任意时间可能存在的空闲连接数
   */
  protected int poolMaximumIdleConnections = 5;
  /**
   * 在被强制返回之前，池中连接被检出（checked out）时间。单位：毫秒
   */
  protected int poolMaximumCheckoutTime = 20000;
  /**
   * 这是一个底层设置，如果获取连接花费了相当长的时间，连接池会打印状态日志并重新尝试获取一个连接（避免在误配置的情况下一直安静的失败）。单位：毫秒
   */
  protected int poolTimeToWait = 20000;
  /**
   * 这是一个关于坏连接容忍度的底层设置，作用于每一个尝试从缓存池获取连接的线程. 如果这个线程获取到的是一个坏的连接，那么这个数据源允许这个线程尝试重新获取一个新的连接，但是这个重新尝试的次数不应该超过 poolMaximumIdleConnections 与 poolMaximumLocalBadConnectionTolerance 之和。
   */
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  /**
   * 发送到数据库的侦测查询，用来检验连接是否正常工作并准备接受请求。
   */
  protected String poolPingQuery = "NO PING QUERY SET";
  /**
   * 是否启用侦测查询。若开启，需要设置 poolPingQuery 属性为一个可执行的 SQL 语句（最好是一个速度非常快的 SQL 语句）
   */
  protected boolean poolPingEnabled;
  /**
   * 配置 poolPingQuery 的频率。可以被设置为和数据库连接超时时间一样，来避免不必要的侦测，默认值：0（即所有连接每一时刻都被侦测 — 当然仅当 poolPingEnabled 为 true 时适用）
   */
  protected int poolPingConnectionsNotUsedFor;
  /**
   * 期望 Connection 的类型编码，通过 {@link #assembleConnectionTypeCode(String, String, String)} 计算。
   */
  private int expectedConnectionTypeCode;

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  @Override
  public Connection getConnection() throws SQLException {
    // 返回 Connection 的代理对象
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * Sets the default network timeout value to wait for the database operation to complete. See {@link Connection#setNetworkTimeout(java.util.concurrent.Executor, int)}
   *
   * @param milliseconds
   *          The time in milliseconds to wait for the database operation to complete.
   * @since 3.5.2
   */
  public void setDefaultNetworkTimeout(Integer milliseconds) {
    dataSource.setDefaultNetworkTimeout(milliseconds);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections.
   *
   * @param poolMaximumActiveConnections
   *          The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections.
   *
   * @param poolMaximumIdleConnections
   *          The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread
   * which are applying for new {@link PooledConnection}.
   *
   * @param poolMaximumLocalBadConnectionTolerance
   *          max tolerance for bad connection happens in one thread
   *
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(
      int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be
   * given away again.
   *
   * @param poolMaximumCheckoutTime
   *          The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection.
   *
   * @param poolTimeToWait
   *          The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection.
   *
   * @param poolPingQuery
   *          The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled
   *          True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the
   * database to make sure the connection is still good.
   *
   * @param milliseconds
   *          the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  /**
   * Gets the default network timeout.
   *
   * @return the default network timeout
   * @since 3.5.2
   */
  public Integer getDefaultNetworkTimeout() {
    return dataSource.getDefaultNetworkTimeout();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  /**
   * Closes all active and idle connections in the pool.
   * 关闭所有的 activeConnections 和 idleConnections 的连接
   */
  public void forceCloseAll() {
    synchronized (state) {
      // 计算 expectedConnectionTypeCode
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
      // 遍历 activeConnections ，进行关闭
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          // 设置为失效
          PooledConnection conn = state.activeConnections.remove(i - 1);
          conn.invalidate();

          Connection realConn = conn.getRealConnection();
          // 回滚事务，如果有事务未提交或回滚
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          PooledConnection conn = state.idleConnections.remove(i - 1);
          conn.invalidate();

          Connection realConn = conn.getRealConnection();
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          // 关闭真实的连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  //相比于获取连接，回收连接的逻辑要简单的多。回收连接成功与否只取决于空闲连接集合的状态，所需处理情况很少，
  protected void pushConnection(PooledConnection conn) throws SQLException {

    synchronized (state) {
      // 从活跃连接池中移除连接
      state.activeConnections.remove(conn);
      if (conn.isValid()) {
        // 空闲连接集合未满
        if (state.idleConnections.size() < poolMaximumIdleConnections && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          // 回滚未提交的事务
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 创建新的 PooledConnection
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          state.idleConnections.add(newConn);
          // 复用时间信息
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          // 将原连接置为无效状态
          conn.invalidate();
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          // 通知等待的线程
          state.notifyAll();
        } else {   // 空闲连接集合已满
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          // 回滚未提交的事务
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 关闭数据库连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          conn.invalidate();
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
        }
        state.badConnectionCount++;
      }
    }
  }

  private PooledConnection popConnection(String username, String password) throws SQLException {
    boolean countedWait = false;
    PooledConnection conn = null;
    long t = System.currentTimeMillis();
    int localBadConnectionCount = 0;

    while (conn == null) {
      synchronized (state) {
        // 检测空闲连接集合（idleConnections）是否为空
        if (!state.idleConnections.isEmpty()) {
          // Pool has available connection
          // idleConnections 不为空，表示有空闲连接可以使用
          conn = state.idleConnections.remove(0);
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
        } else {
          // Pool does not have available connection
          // 暂无空闲连接可用，但如果活跃连接数还未超出限制
          //（poolMaximumActiveConnections），则可创建新的连接
          if (state.activeConnections.size() < poolMaximumActiveConnections) {
            // Can create new connection
            // 创建新连接
            conn = new PooledConnection(dataSource.getConnection(), this);
            if (log.isDebugEnabled()) {
              log.debug("Created connection " + conn.getRealHashCode() + ".");
            }
          } else {// 连接池已满，不能创建新连接
            // Cannot create new connection
            // 取出运行时间最长的连接
            PooledConnection oldestActiveConnection = state.activeConnections.get(0);
            // 获取运行时长
            long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
            // 检测运行时长是否超出限制，即超时
            if (longestCheckoutTime > poolMaximumCheckoutTime) {
              // Can claim overdue connection
              // 累加超时相关的统计字段
              state.claimedOverdueConnectionCount++;
              state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
              state.accumulatedCheckoutTime += longestCheckoutTime;
              // 从活跃连接集合中移除超时连接
              state.activeConnections.remove(oldestActiveConnection);
              // 若连接未设置自动提交，此处进行回滚操作
              if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
                try {
                  oldestActiveConnection.getRealConnection().rollback();
                } catch (SQLException e) {
                  /*
                     Just log a message for debug and continue to execute the following
                     statement like nothing happened.
                     Wrap the bad connection with a new PooledConnection, this will help
                     to not interrupt current executing thread and give current thread a
                     chance to join the next competition for another valid/good database
                     connection. At the end of this loop, bad {@link @conn} will be set as null.
                   */
                  log.debug("Bad connection. Could not roll back");
                }
              }
              /*
               * 创建一个新的 PooledConnection，注意，
               * 此处复用 oldestActiveConnection 的 realConnection 变量
               */
              conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
              /*
               * 复用 oldestActiveConnection 的一些信息，注意 PooledConnection 中的
               * createdTimestamp 用于记录 Connection 的创建时间，而非 PooledConnection
               * 的创建时间。所以这里要复用原连接的时间信息。
               */
              conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
              conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
              oldestActiveConnection.invalidate();
              if (log.isDebugEnabled()) {
                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
              }
            } else {
              // Must wait
              // 运行时间最长的连接并未超时 等待
              try {
                if (!countedWait) {
                  state.hadToWaitCount++;
                  countedWait = true;
                }
                if (log.isDebugEnabled()) {
                  log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                }
                long wt = System.currentTimeMillis();
                // 当前线程进入等待状态
                state.wait(poolTimeToWait);
                state.accumulatedWaitTime += System.currentTimeMillis() - wt;
              } catch (InterruptedException e) {
                // set interrupt flag
                Thread.currentThread().interrupt();
                break;
              }
            }
          }
        }
        if (conn != null) {
          // ping to server and check the connection is valid or not
          /*
           * 检测连接是否有效，isValid 方法除了会检测 valid 是否为 true，
           * 还会通过 PooledConnection 的 pingConnection 方法执行 SQL 语句，
           * 检测连接是否可用。pingConnection 方法的逻辑不复杂，大家可以自行分析。
           * 另外，官方文档在介绍 POOLED 类型数据源时，也介绍了连接有效性检测方面的
           * 属性，有三个：poolPingQuery，poolPingEnabled 和
           * poolPingConnectionsNotUsedFor。关于这三个属性，大家可以查阅官方文档
           */
          if (conn.isValid()) {
            //若连接未设置自动提交，此处进行回滚操作
            if (!conn.getRealConnection().getAutoCommit()) {
              // 进行回滚操作
              conn.getRealConnection().rollback();
            }
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            // 设置统计字段
            conn.setCheckoutTimestamp(System.currentTimeMillis());
            conn.setLastUsedTimestamp(System.currentTimeMillis());
            state.activeConnections.add(conn);
            state.requestCount++;
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else {
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
            }
            // 连接无效，此时累加无效连接相关的统计字段
            state.badConnectionCount++;
            localBadConnectionCount++;
            conn = null;
            if (localBadConnectionCount > (poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance)) {
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      }

    }

    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    return conn;
  }

  /**
   * Method to check to see if a connection is still usable
   *
   * @param conn
   *          - the connection to check
   * @return True if the connection is still usable
   */
  protected boolean pingConnection(PooledConnection conn) {
    // 记录是否 ping 成功
    boolean result = true;

    try {
      // 判断真实的连接是否已经关闭。若已关闭，就意味着 ping 肯定是失败的。
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }

    // 是否启用侦测查询 、 判断是否长时间未使用。若是，才需要发起 ping
    if (result && poolPingEnabled && poolPingConnectionsNotUsedFor >= 0
        && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
      try {
        if (log.isDebugEnabled()) {
          log.debug("Testing connection " + conn.getRealHashCode() + " ...");
        }
        // 通过执行 poolPingQuery 语句来发起 ping
        Connection realConn = conn.getRealConnection();
        try (Statement statement = realConn.createStatement()) {
          statement.executeQuery(poolPingQuery).close();
        }
        if (!realConn.getAutoCommit()) {
          realConn.rollback();
        }
        // 标记执行成功
        result = true;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
        }
      } catch (Exception e) {
        log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
        try {
          // 关闭数据库真实的连接
          conn.getRealConnection().close();
        } catch (Exception e2) {
          // ignore
        }
        // 标记执行失败
        result = false;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
        }
      }
    }
    return result;
  }

  /**
   * Unwraps a pooled connection to get to the 'real' connection
   *
   * @param conn
   *          - the pooled connection to unwrap
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    // 如果传入的是被代理的连接
    if (Proxy.isProxyClass(conn.getClass())) {
      // 获取 InvocationHandler 对象
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      // 如果是 PooledConnection 对象，则获取真实的连接
      if (handler instanceof PooledConnection) {
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  @Override
  protected void finalize() throws Throwable {
    // 关闭所有连接
    forceCloseAll();
    // 执行对象销毁
    super.finalize();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
  }

}
