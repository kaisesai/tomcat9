/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.jdbc.pool.jmx;

import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanNotificationInfo;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.jdbc.pool.PoolConfiguration;
import org.apache.tomcat.jdbc.pool.PoolProperties.InterceptorDefinition;
import org.apache.tomcat.jdbc.pool.PoolUtilities;
import org.apache.tomcat.jdbc.pool.Validator;

public class ConnectionPool extends NotificationBroadcasterSupport
  implements ConnectionPoolMBean, MBeanRegistration {
  
  //=================================================================
  //       NOTIFICATION INFO
  //=================================================================
  public static final String NOTIFY_INIT = "INIT FAILED";
  
  public static final String NOTIFY_CONNECT = "CONNECTION FAILED";
  
  public static final String NOTIFY_ABANDON = "CONNECTION ABANDONED";
  
  public static final String SLOW_QUERY_NOTIFICATION = "SLOW QUERY";
  
  public static final String FAILED_QUERY_NOTIFICATION = "FAILED QUERY";
  
  public static final String SUSPECT_ABANDONED_NOTIFICATION = "SUSPECT CONNECTION ABANDONED";
  
  public static final String POOL_EMPTY = "POOL EMPTY";
  
  public static final String SUSPECT_RETURNED_NOTIFICATION = "SUSPECT CONNECTION RETURNED";
  
  /**
   * logger
   */
  private static final Log log = LogFactory.getLog(ConnectionPool.class);
  
  /**
   * the connection pool
   */
  protected org.apache.tomcat.jdbc.pool.ConnectionPool pool = null;
  
  /**
   * sequence for JMX notifications
   */
  protected AtomicInteger sequence = new AtomicInteger(0);
  
  /**
   * Listeners that are local and interested in our notifications, no need for JMX
   */
  protected ConcurrentLinkedQueue<NotificationListener> listeners = new ConcurrentLinkedQueue<>();
  
  /**
   * the ObjectName of this pool.
   */
  private ObjectName oname = null;
  
  public ConnectionPool(org.apache.tomcat.jdbc.pool.ConnectionPool pool) {
    super();
    this.pool = pool;
  }
  
  public static MBeanNotificationInfo[] getDefaultNotificationInfo() {
    String[] types = new String[] {NOTIFY_INIT, NOTIFY_CONNECT, NOTIFY_ABANDON,
      SLOW_QUERY_NOTIFICATION, FAILED_QUERY_NOTIFICATION, SUSPECT_ABANDONED_NOTIFICATION,
      POOL_EMPTY, SUSPECT_RETURNED_NOTIFICATION};
    String name = Notification.class.getName();
    String description = "A connection pool error condition was met.";
    MBeanNotificationInfo info = new MBeanNotificationInfo(types, name, description);
    return new MBeanNotificationInfo[] {info};
  }
  
  public org.apache.tomcat.jdbc.pool.ConnectionPool getPool() {
    return pool;
  }
  
  public PoolConfiguration getPoolProperties() {
    return pool.getPoolProperties();
  }
  
  public ObjectName getObjectName() {
    return oname;
  }
  
  @Override
  public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
    this.oname = name;
    return name;
  }
  
  @Override
  public void postRegister(Boolean registrationDone) {
  }
  
  @Override
  public void preDeregister() throws Exception {
  }
  
  @Override
  public void postDeregister() {
  }
  
  @Override
  public MBeanNotificationInfo[] getNotificationInfo() {
    MBeanNotificationInfo[] pres = super.getNotificationInfo();
    MBeanNotificationInfo[] loc = getDefaultNotificationInfo();
    MBeanNotificationInfo[] aug = new MBeanNotificationInfo[pres.length + loc.length];
    if (pres.length > 0) {
      System.arraycopy(pres, 0, aug, 0, pres.length);
    }
    if (loc.length > 0) {
      System.arraycopy(loc, 0, aug, pres.length, loc.length);
    }
    return aug;
  }
  
  /**
   * Return true if the notification was sent successfully, false otherwise.
   *
   * @param type    The notification type
   * @param message The message
   * @return true if the notification succeeded
   */
  public boolean notify(final String type, String message) {
    try {
      Notification n = new Notification(type, this, sequence.incrementAndGet(),
                                        System.currentTimeMillis(), "[" + type + "] " + message);
      sendNotification(n);
      for (NotificationListener listener : listeners) {
        listener.handleNotification(n, this);
      }
      return true;
    } catch (Exception x) {
      if (log.isDebugEnabled()) {
        log.debug("Notify failed. Type=" + type + "; Message=" + message, x);
      }
      return false;
    }
    
  }
  
  public void addListener(NotificationListener list) {
    listeners.add(list);
  }
  
  public boolean removeListener(NotificationListener list) {
    return listeners.remove(list);
  }
  
  //=================================================================
  //       POOL STATS
  //=================================================================
  
  @Override
  public int getSize() {
    return pool.getSize();
  }
  
  @Override
  public int getIdle() {
    return pool.getIdle();
  }
  
  @Override
  public int getActive() {
    return pool.getActive();
  }
  
  @Override
  public int getNumIdle() {
    return getIdle();
  }
  
  @Override
  public int getNumActive() {
    return getActive();
  }
  
  @Override
  public int getWaitCount() {
    return pool.getWaitCount();
  }
  
  @Override
  public long getBorrowedCount() {
    return pool.getBorrowedCount();
  }
  
  @Override
  public long getReturnedCount() {
    return pool.getReturnedCount();
  }
  
  @Override
  public long getCreatedCount() {
    return pool.getCreatedCount();
  }
  
  @Override
  public long getReleasedCount() {
    return pool.getReleasedCount();
  }
  
  @Override
  public long getReconnectedCount() {
    return pool.getReconnectedCount();
  }
  
  @Override
  public long getRemoveAbandonedCount() {
    return pool.getRemoveAbandonedCount();
  }
  
  @Override
  public long getReleasedIdleCount() {
    return pool.getReleasedIdleCount();
  }
  
  //=================================================================
  //       POOL OPERATIONS
  //=================================================================
  @Override
  public void checkIdle() {
    pool.checkIdle();
  }
  
  @Override
  public void checkAbandoned() {
    pool.checkAbandoned();
  }
  
  @Override
  public void testIdle() {
    pool.testAllIdle();
  }
  
  @Override
  public void resetStats() {
    pool.resetStats();
  }
  
  //=================================================================
  //       POOL PROPERTIES
  //=================================================================
  //=========================================================
  //  PROPERTIES / CONFIGURATION
  //=========================================================
  
  @Override
  public String getConnectionProperties() {
    return getPoolProperties().getConnectionProperties();
  }
  
  @Override
  public void setConnectionProperties(String connectionProperties) {
    getPoolProperties().setConnectionProperties(connectionProperties);
    
  }
  
  @Override
  public Properties getDbProperties() {
    return PoolUtilities.cloneWithoutPassword(getPoolProperties().getDbProperties());
  }
  
  @Override
  public void setDbProperties(Properties dbProperties) {
    getPoolProperties().setDbProperties(dbProperties);
  }
  
  @Override
  public String getDefaultCatalog() {
    return getPoolProperties().getDefaultCatalog();
  }
  
  @Override
  public void setDefaultCatalog(String defaultCatalog) {
    getPoolProperties().setDefaultCatalog(defaultCatalog);
  }
  
  @Override
  public int getDefaultTransactionIsolation() {
    return getPoolProperties().getDefaultTransactionIsolation();
  }
  
  @Override
  public void setDefaultTransactionIsolation(int defaultTransactionIsolation) {
    getPoolProperties().setDefaultTransactionIsolation(defaultTransactionIsolation);
  }
  
  @Override
  public String getDriverClassName() {
    return getPoolProperties().getDriverClassName();
  }
  
  @Override
  public void setDriverClassName(String driverClassName) {
    getPoolProperties().setDriverClassName(driverClassName);
  }
  
  @Override
  public int getInitialSize() {
    return getPoolProperties().getInitialSize();
  }
  
  @Override
  public void setInitialSize(int initialSize) {
    // noop - this pool is already running
    throw new UnsupportedOperationException();
    
  }
  
  @Override
  public String getInitSQL() {
    return getPoolProperties().getInitSQL();
  }
  
  @Override
  public void setInitSQL(String initSQL) {
    getPoolProperties().setInitSQL(initSQL);
    
  }
  
  @Override
  public String getJdbcInterceptors() {
    return getPoolProperties().getJdbcInterceptors();
  }
  
  @Override
  public void setJdbcInterceptors(String jdbcInterceptors) {
    // noop - this pool is already running
    throw new UnsupportedOperationException();
  }
  
  @Override
  public int getMaxActive() {
    return getPoolProperties().getMaxActive();
  }
  
  @Override
  public void setMaxActive(int maxActive) {
    getPoolProperties().setMaxActive(maxActive);
    //make sure the pool is properly configured
    pool.checkPoolConfiguration(getPoolProperties());
  }
  
  @Override
  public int getMaxIdle() {
    return getPoolProperties().getMaxIdle();
  }
  
  @Override
  public void setMaxIdle(int maxIdle) {
    getPoolProperties().setMaxIdle(maxIdle);
    //make sure the pool is properly configured
    pool.checkPoolConfiguration(getPoolProperties());
    
  }
  
  @Override
  public int getMaxWait() {
    return getPoolProperties().getMaxWait();
  }
  
  @Override
  public void setMaxWait(int maxWait) {
    getPoolProperties().setMaxWait(maxWait);
  }
  
  @Override
  public int getMinEvictableIdleTimeMillis() {
    return getPoolProperties().getMinEvictableIdleTimeMillis();
  }
  
  @Override
  public void setMinEvictableIdleTimeMillis(int minEvictableIdleTimeMillis) {
    boolean wasEnabled = getPoolProperties().isPoolSweeperEnabled();
    getPoolProperties().setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
    poolCleanerAttributeUpdated(wasEnabled);
  }
  
  @Override
  public int getMinIdle() {
    return getPoolProperties().getMinIdle();
  }
  
  @Override
  public void setMinIdle(int minIdle) {
    getPoolProperties().setMinIdle(minIdle);
    //make sure the pool is properly configured
    pool.checkPoolConfiguration(getPoolProperties());
  }
  
  @Override
  public long getMaxAge() {
    return getPoolProperties().getMaxAge();
  }
  
  @Override
  public void setMaxAge(long maxAge) {
    boolean wasEnabled = getPoolProperties().isPoolSweeperEnabled();
    getPoolProperties().setMaxAge(maxAge);
    //make sure the pool is properly configured
    pool.checkPoolConfiguration(getPoolProperties());
    poolCleanerAttributeUpdated(wasEnabled);
  }
  
  @Override
  public String getName() {
    return this.getPoolName();
  }
  
  @Override
  public void setName(String name) {
    getPoolProperties().setName(name);
  }
  
  @Override
  public int getNumTestsPerEvictionRun() {
    return getPoolProperties().getNumTestsPerEvictionRun();
  }
  
  @Override
  public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
    getPoolProperties().setNumTestsPerEvictionRun(numTestsPerEvictionRun);
  }
  
  /**
   * @return DOES NOT RETURN THE PASSWORD, IT WOULD SHOW UP IN JMX
   */
  @Override
  public String getPassword() {
    return "Password not available as DataSource/JMX operation.";
  }
  
  @Override
  public void setPassword(String password) {
    getPoolProperties().setPassword(password);
  }
  
  @Override
  public int getRemoveAbandonedTimeout() {
    return getPoolProperties().getRemoveAbandonedTimeout();
  }
  
  @Override
  public void setRemoveAbandonedTimeout(int removeAbandonedTimeout) {
    boolean wasEnabled = getPoolProperties().isPoolSweeperEnabled();
    getPoolProperties().setRemoveAbandonedTimeout(removeAbandonedTimeout);
    poolCleanerAttributeUpdated(wasEnabled);
  }
  
  @Override
  public int getTimeBetweenEvictionRunsMillis() {
    return getPoolProperties().getTimeBetweenEvictionRunsMillis();
  }
  
  @Override
  public void setTimeBetweenEvictionRunsMillis(int timeBetweenEvictionRunsMillis) {
    boolean wasEnabled = getPoolProperties().isPoolSweeperEnabled();
    getPoolProperties().setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
    //make sure the pool is properly configured
    pool.checkPoolConfiguration(getPoolProperties());
    poolCleanerAttributeUpdated(wasEnabled);
  }
  
  @Override
  public String getUrl() {
    return getPoolProperties().getUrl();
  }
  
  @Override
  public void setUrl(String url) {
    getPoolProperties().setUrl(url);
  }
  
  @Override
  public String getUsername() {
    return getPoolProperties().getUsername();
  }
  
  @Override
  public void setUsername(String username) {
    getPoolProperties().setUsername(username);
  }
  
  @Override
  public long getValidationInterval() {
    return getPoolProperties().getValidationInterval();
  }
  
  @Override
  public void setValidationInterval(long validationInterval) {
    getPoolProperties().setValidationInterval(validationInterval);
  }
  
  @Override
  public String getValidationQuery() {
    return getPoolProperties().getValidationQuery();
  }
  
  @Override
  public void setValidationQuery(String validationQuery) {
    getPoolProperties().setValidationQuery(validationQuery);
  }
  
  @Override
  public int getValidationQueryTimeout() {
    return getPoolProperties().getValidationQueryTimeout();
  }
  
  @Override
  public void setValidationQueryTimeout(int validationQueryTimeout) {
    getPoolProperties().setValidationQueryTimeout(validationQueryTimeout);
  }
  
  /**
   * {@inheritDoc}
   */
  
  @Override
  public String getValidatorClassName() {
    return getPoolProperties().getValidatorClassName();
  }
  
  /**
   * {@inheritDoc}
   */
  
  @Override
  public void setValidatorClassName(String className) {
    getPoolProperties().setValidatorClassName(className);
  }
  
  /**
   * {@inheritDoc}
   */
  
  @Override
  public Validator getValidator() {
    return getPoolProperties().getValidator();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setValidator(Validator validator) {
    getPoolProperties().setValidator(validator);
  }
  
  @Override
  public boolean isAccessToUnderlyingConnectionAllowed() {
    return getPoolProperties().isAccessToUnderlyingConnectionAllowed();
  }
  
  @Override
  public void setAccessToUnderlyingConnectionAllowed(boolean accessToUnderlyingConnectionAllowed) {
    getPoolProperties().setAccessToUnderlyingConnectionAllowed(accessToUnderlyingConnectionAllowed);
  }
  
  @Override
  public Boolean isDefaultAutoCommit() {
    return getPoolProperties().isDefaultAutoCommit();
  }
  
  @Override
  public Boolean isDefaultReadOnly() {
    return getPoolProperties().isDefaultReadOnly();
  }
  
  @Override
  public boolean isLogAbandoned() {
    return getPoolProperties().isLogAbandoned();
  }
  
  @Override
  public void setLogAbandoned(boolean logAbandoned) {
    getPoolProperties().setLogAbandoned(logAbandoned);
  }
  
  @Override
  public boolean isPoolSweeperEnabled() {
    return getPoolProperties().isPoolSweeperEnabled();
  }
  
  @Override
  public boolean isRemoveAbandoned() {
    return getPoolProperties().isRemoveAbandoned();
  }
  
  @Override
  public void setRemoveAbandoned(boolean removeAbandoned) {
    boolean wasEnabled = getPoolProperties().isPoolSweeperEnabled();
    getPoolProperties().setRemoveAbandoned(removeAbandoned);
    poolCleanerAttributeUpdated(wasEnabled);
  }
  
  @Override
  public int getAbandonWhenPercentageFull() {
    return getPoolProperties().getAbandonWhenPercentageFull();
  }
  
  @Override
  public void setAbandonWhenPercentageFull(int percentage) {
    getPoolProperties().setAbandonWhenPercentageFull(percentage);
  }
  
  @Override
  public boolean isTestOnBorrow() {
    return getPoolProperties().isTestOnBorrow();
  }
  
  @Override
  public void setTestOnBorrow(boolean testOnBorrow) {
    getPoolProperties().setTestOnBorrow(testOnBorrow);
  }
  
  @Override
  public boolean isTestOnConnect() {
    return getPoolProperties().isTestOnConnect();
  }
  
  @Override
  public void setTestOnConnect(boolean testOnConnect) {
    getPoolProperties().setTestOnConnect(testOnConnect);
  }
  
  @Override
  public boolean isTestOnReturn() {
    return getPoolProperties().isTestOnReturn();
  }
  
  @Override
  public void setTestOnReturn(boolean testOnReturn) {
    getPoolProperties().setTestOnReturn(testOnReturn);
  }
  
  @Override
  public boolean isTestWhileIdle() {
    return getPoolProperties().isTestWhileIdle();
  }
  
  @Override
  public void setTestWhileIdle(boolean testWhileIdle) {
    boolean wasEnabled = getPoolProperties().isPoolSweeperEnabled();
    getPoolProperties().setTestWhileIdle(testWhileIdle);
    poolCleanerAttributeUpdated(wasEnabled);
  }
  
  @Override
  public Boolean getDefaultAutoCommit() {
    return getPoolProperties().getDefaultAutoCommit();
  }
  
  @Override
  public void setDefaultAutoCommit(Boolean defaultAutoCommit) {
    getPoolProperties().setDefaultAutoCommit(defaultAutoCommit);
  }
  
  @Override
  public Boolean getDefaultReadOnly() {
    return getPoolProperties().getDefaultReadOnly();
  }
  
  @Override
  public void setDefaultReadOnly(Boolean defaultReadOnly) {
    getPoolProperties().setDefaultReadOnly(defaultReadOnly);
  }
  
  @Override
  public InterceptorDefinition[] getJdbcInterceptorsAsArray() {
    return getPoolProperties().getJdbcInterceptorsAsArray();
  }
  
  @Override
  public boolean getUseLock() {
    return getPoolProperties().getUseLock();
  }
  
  @Override
  public void setUseLock(boolean useLock) {
    getPoolProperties().setUseLock(useLock);
  }
  
  @Override
  public boolean isFairQueue() {
    return getPoolProperties().isFairQueue();
  }
  
  @Override
  public void setFairQueue(boolean fairQueue) {
    // noop - this pool is already running
    throw new UnsupportedOperationException();
  }
  
  @Override
  public boolean isJmxEnabled() {
    return getPoolProperties().isJmxEnabled();
  }
  
  @Override
  public void setJmxEnabled(boolean jmxEnabled) {
    // noop - this pool is already running and obviously jmx enabled
    throw new UnsupportedOperationException();
  }
  
  @Override
  public boolean isUseEquals() {
    return getPoolProperties().isUseEquals();
  }
  
  @Override
  public void setUseEquals(boolean useEquals) {
    getPoolProperties().setUseEquals(useEquals);
  }
  
  @Override
  public String getPoolName() {
    return getPoolProperties().getName();
  }
  
  /**
   * Starts/stops pool cleaner thread as necessary after its configuration properties
   * were updated.
   * <p>
   * This method must be called <b>after</b> configuration properties affecting the pool cleaner
   * have been updated.
   *
   * @param wasEnabled whether the pool cleaner was enabled <b>before</b> the configuration change occurred.
   */
  private void poolCleanerAttributeUpdated(boolean wasEnabled) {
    boolean shouldBeEnabled = getPoolProperties().isPoolSweeperEnabled();
    //make sure pool cleaner starts/stops when it should
    if (!wasEnabled && shouldBeEnabled) {
      pool.initializePoolCleaner(getPoolProperties());
    } else if (wasEnabled) {
      pool.terminatePoolCleaner();
      if (shouldBeEnabled) {
        pool.initializePoolCleaner(getPoolProperties());
      }
    }
  }
  
  /**
   * {@inheritDoc}
   */
  
  @Override
  public int getSuspectTimeout() {
    return getPoolProperties().getSuspectTimeout();
  }
  
  /**
   * {@inheritDoc}
   */
  
  @Override
  public void setSuspectTimeout(int seconds) {
    getPoolProperties().setSuspectTimeout(seconds);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public Object getDataSource() {
    return getPoolProperties().getDataSource();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setDataSource(Object ds) {
    getPoolProperties().setDataSource(ds);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public String getDataSourceJNDI() {
    return getPoolProperties().getDataSourceJNDI();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setDataSourceJNDI(String jndiDS) {
    getPoolProperties().setDataSourceJNDI(jndiDS);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isAlternateUsernameAllowed() {
    return getPoolProperties().isAlternateUsernameAllowed();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setAlternateUsernameAllowed(boolean alternateUsernameAllowed) {
    getPoolProperties().setAlternateUsernameAllowed(alternateUsernameAllowed);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getCommitOnReturn() {
    return getPoolProperties().getCommitOnReturn();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setCommitOnReturn(boolean commitOnReturn) {
    getPoolProperties().setCommitOnReturn(commitOnReturn);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getRollbackOnReturn() {
    return getPoolProperties().getRollbackOnReturn();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setRollbackOnReturn(boolean rollbackOnReturn) {
    getPoolProperties().setRollbackOnReturn(rollbackOnReturn);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getUseDisposableConnectionFacade() {
    return getPoolProperties().getUseDisposableConnectionFacade();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setUseDisposableConnectionFacade(boolean useDisposableConnectionFacade) {
    getPoolProperties().setUseDisposableConnectionFacade(useDisposableConnectionFacade);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getLogValidationErrors() {
    return getPoolProperties().getLogValidationErrors();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setLogValidationErrors(boolean logValidationErrors) {
    getPoolProperties().setLogValidationErrors(logValidationErrors);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getPropagateInterruptState() {
    return getPoolProperties().getPropagateInterruptState();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setPropagateInterruptState(boolean propagateInterruptState) {
    getPoolProperties().setPropagateInterruptState(propagateInterruptState);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isIgnoreExceptionOnPreLoad() {
    return getPoolProperties().isIgnoreExceptionOnPreLoad();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setIgnoreExceptionOnPreLoad(boolean ignoreExceptionOnPreLoad) {
    // noop - this pool is already running
    throw new UnsupportedOperationException();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getUseStatementFacade() {
    return getPoolProperties().getUseStatementFacade();
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void setUseStatementFacade(boolean useStatementFacade) {
    getPoolProperties().setUseStatementFacade(useStatementFacade);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void purge() {
    pool.purge();
    
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void purgeOnReturn() {
    pool.purgeOnReturn();
    
  }
  
}
