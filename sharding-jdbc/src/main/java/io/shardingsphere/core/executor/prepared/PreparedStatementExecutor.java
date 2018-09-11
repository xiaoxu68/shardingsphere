/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.core.executor.prepared;

import io.shardingsphere.core.constant.ConnectionMode;
import io.shardingsphere.core.constant.SQLType;
import io.shardingsphere.core.executor.ShardingExecuteGroup;
import io.shardingsphere.core.executor.StatementExecuteUnit;
import io.shardingsphere.core.executor.sql.SQLExecuteUnit;
import io.shardingsphere.core.executor.sql.execute.SQLExecuteCallback;
import io.shardingsphere.core.executor.sql.execute.SQLExecuteTemplate;
import io.shardingsphere.core.executor.sql.execute.result.MemoryQueryResult;
import io.shardingsphere.core.executor.sql.execute.result.StreamQueryResult;
import io.shardingsphere.core.executor.sql.execute.threadlocal.ExecutorDataMap;
import io.shardingsphere.core.executor.sql.execute.threadlocal.ExecutorExceptionHandler;
import io.shardingsphere.core.executor.sql.prepare.SQLExecutePrepareCallback;
import io.shardingsphere.core.executor.sql.prepare.SQLExecutePrepareTemplate;
import io.shardingsphere.core.jdbc.core.connection.ShardingConnection;
import io.shardingsphere.core.merger.QueryResult;
import io.shardingsphere.core.routing.RouteUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Prepared statement executor.
 * 
 * @author zhangliang
 * @author caohao
 * @author maxiaoguang
 */
@RequiredArgsConstructor
public abstract class PreparedStatementExecutor {
    
    private final SQLType sqlType;
    
    private final int resultSetType;
    
    private final int resultSetConcurrency;
    
    private final int resultSetHoldability;
    
    private boolean returnGeneratedKeys;
    
    private final ShardingConnection connection;
    
    private final Collection<RouteUnit> routeUnits;
    
    private final SQLExecuteTemplate sqlExecuteTemplate;
    
    private final SQLExecutePrepareTemplate sqlExecutePrepareTemplate;
    
    @Getter
    private Collection<ResultSet> resultSets = new LinkedList<>();
    
    @Getter
    private Collection<Statement> statements = new LinkedList<>();
    
    public PreparedStatementExecutor(final SQLType sqlType, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final boolean returnGeneratedKeys, final ShardingConnection shardingConnection, final Collection<RouteUnit> routeUnits) {
        this.sqlType = sqlType;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
        this.returnGeneratedKeys = returnGeneratedKeys;
        this.routeUnits = routeUnits;
        this.connection = shardingConnection;
        sqlExecuteTemplate = new SQLExecuteTemplate(connection.getShardingDataSource().getShardingContext().getExecuteEngine());
        sqlExecutePrepareTemplate = new SQLExecutePrepareTemplate(connection.getShardingDataSource().getShardingContext().getMaxConnectionsSizePerQuery());
    }
    
    /**
     * Execute query.
     *
     * @return result set list
     * @throws SQLException SQL exception
     */
    public List<QueryResult> executeQuery() throws SQLException {
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        SQLExecuteCallback<QueryResult> executeCallback = new SQLExecuteCallback<QueryResult>(sqlType, isExceptionThrown, dataMap) {
            
            @Override
            protected QueryResult executeSQL(final SQLExecuteUnit sqlExecuteUnit) throws SQLException {
                return getQueryResult(sqlExecuteUnit);
            }
        };
        return executeCallback(executeCallback);
    }
    
    private QueryResult getQueryResult(final SQLExecuteUnit sqlExecuteUnit) throws SQLException {
        ResultSet resultSet = ((PreparedStatement) sqlExecuteUnit.getStatement()).executeQuery();
        statements.add(sqlExecuteUnit.getStatement());
        resultSets.add(resultSet);
        return ConnectionMode.MEMORY_STRICTLY == sqlExecuteUnit.getConnectionMode() ? new StreamQueryResult(resultSet) : new MemoryQueryResult(resultSet);
    }
    
    /**
     * Execute update.
     * 
     * @return effected records count
     * @throws SQLException SQL exception
     */
    public int executeUpdate() throws SQLException {
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        SQLExecuteCallback<Integer> executeCallback = new SQLExecuteCallback<Integer>(sqlType, isExceptionThrown, dataMap) {
            
            @Override
            protected Integer executeSQL(final SQLExecuteUnit sqlExecuteUnit) throws SQLException {
                return ((PreparedStatement) sqlExecuteUnit.getStatement()).executeUpdate();
            }
        };
        List<Integer> results = executeCallback(executeCallback);
        return accumulate(results);
    }
    
    private int accumulate(final List<Integer> results) {
        int result = 0;
        for (Integer each : results) {
            result += null == each ? 0 : each;
        }
        return result;
    }
    
    /**
     * Execute SQL.
     *
     * @return return true if is DQL, false if is DML
     * @throws SQLException SQL exception
     */
    public boolean execute() throws SQLException {
        boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        SQLExecuteCallback<Boolean> executeCallback = new SQLExecuteCallback<Boolean>(sqlType, isExceptionThrown, dataMap) {
            
            @Override
            protected Boolean executeSQL(final SQLExecuteUnit sqlExecuteUnit) throws SQLException {
                return ((PreparedStatement) sqlExecuteUnit.getStatement()).execute();
            }
        };
        List<Boolean> result = executeCallback(executeCallback);
        if (null == result || result.isEmpty() || null == result.get(0)) {
            return false;
        }
        return result.get(0);
    }
    
    @SuppressWarnings("unchecked")
    private <T> List<T> executeCallback(final SQLExecuteCallback<T> executeCallback) throws SQLException {
        Collection<ShardingExecuteGroup<SQLExecuteUnit>> executeGroups = sqlExecutePrepareTemplate.getExecuteUnitGroups(routeUnits, new SQLExecutePrepareCallback() {
            
            @Override
            public Connection getConnection(final String dataSourceName) throws SQLException {
                return connection.getConnection(dataSourceName);
            }
            
            @Override
            public SQLExecuteUnit createSQLExecuteUnit(final Connection connection, final RouteUnit routeUnit, final ConnectionMode connectionMode) throws SQLException {
                Statement statement = connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
                return new StatementExecuteUnit(routeUnit, statement, connectionMode);
            }
        });
        return sqlExecuteTemplate.executeGroup((Collection) executeGroups, executeCallback);
    }
}

