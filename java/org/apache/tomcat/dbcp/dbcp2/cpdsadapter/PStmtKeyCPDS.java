/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
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
package org.apache.tomcat.dbcp.dbcp2.cpdsadapter;

import org.apache.tomcat.dbcp.dbcp2.PStmtKey;

/**
 * A key uniquely identifying a {@link java.sql.PreparedStatement PreparedStatement}.
 *
 * @since 2.0
 * @deprecated Use {@link PStmtKey}.
 */
@Deprecated
public class PStmtKeyCPDS extends PStmtKey {
  
  /**
   * Constructs a key to uniquely identify a prepared statement.
   *
   * @param sql The SQL statement.
   */
  public PStmtKeyCPDS(final String sql) {
    super(sql);
  }
  
  /**
   * Constructs a key to uniquely identify a prepared statement.
   *
   * @param sql               The SQL statement.
   * @param autoGeneratedKeys A flag indicating whether auto-generated keys should be returned; one of
   *                          <code>Statement.RETURN_GENERATED_KEYS</code> or <code>Statement.NO_GENERATED_KEYS</code>.
   */
  public PStmtKeyCPDS(final String sql, final int autoGeneratedKeys) {
    super(sql, null, autoGeneratedKeys);
  }
  
  /**
   * Constructs a key to uniquely identify a prepared statement.
   *
   * @param sql                  The SQL statement.
   * @param resultSetType        A result set type; one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
   *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>.
   * @param resultSetConcurrency A concurrency type; one of <code>ResultSet.CONCUR_READ_ONLY</code> or
   *                             <code>ResultSet.CONCUR_UPDATABLE</code>.
   */
  public PStmtKeyCPDS(final String sql, final int resultSetType, final int resultSetConcurrency) {
    super(sql, resultSetType, resultSetConcurrency);
  }
  
  /**
   * Constructs a key to uniquely identify a prepared statement.
   *
   * @param sql                  The SQL statement.
   * @param resultSetType        a result set type; one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
   *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>.
   * @param resultSetConcurrency A concurrency type; one of <code>ResultSet.CONCUR_READ_ONLY</code> or
   *                             <code>ResultSet.CONCUR_UPDATABLE</code>
   * @param resultSetHoldability One of the following <code>ResultSet</code> constants: <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code>
   *                             or <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>.
   */
  public PStmtKeyCPDS(final String sql, final int resultSetType, final int resultSetConcurrency,
                      final int resultSetHoldability) {
    super(sql, null, resultSetType, resultSetConcurrency, resultSetHoldability);
  }
  
  /**
   * Constructs a key to uniquely identify a prepared statement.
   *
   * @param sql           The SQL statement.
   * @param columnIndexes An array of column indexes indicating the columns that should be returned from the inserted row or
   *                      rows.
   */
  public PStmtKeyCPDS(final String sql, final int columnIndexes[]) {
    super(sql, null, columnIndexes);
  }
  
  /**
   * Constructs a key to uniquely identify a prepared statement.
   *
   * @param sql         The SQL statement.
   * @param columnNames An array of column names indicating the columns that should be returned from the inserted row or rows.
   */
  public PStmtKeyCPDS(final String sql, final String columnNames[]) {
    super(sql, null, columnNames);
  }
  
}
