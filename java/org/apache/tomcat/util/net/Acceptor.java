/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.jni.Error;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

public class Acceptor<U> implements Runnable {
  
  private static final Log log = LogFactory.getLog(Acceptor.class);
  
  private static final StringManager sm = StringManager.getManager(Acceptor.class);
  
  private static final int INITIAL_ERROR_DELAY = 50;
  
  private static final int MAX_ERROR_DELAY = 1600;
  
  /**
   * 协议端点
   */
  private final AbstractEndpoint<?, U> endpoint;
  
  private final CountDownLatch stopLatch = new CountDownLatch(1);
  
  protected volatile AcceptorState state = AcceptorState.NEW;
  
  private String threadName;
  
  /*
   * Tracked separately rather than using endpoint.isRunning() as calls to
   * endpoint.stop() and endpoint.start() in quick succession can cause the
   * acceptor to continue running when it should terminate.
   */
  private volatile boolean stopCalled = false;
  
  public Acceptor(AbstractEndpoint<?, U> endpoint) {
    this.endpoint = endpoint;
  }
  
  public final AcceptorState getState() {
    return state;
  }
  
  final String getThreadName() {
    return threadName;
  }
  
  final void setThreadName(final String threadName) {
    this.threadName = threadName;
  }
  
  @Override
  public void run() {
    
    int errorDelay = 0;
    
    try {
      // 轮询，直到接收到 shutdown 命令
      // Loop until we receive a shutdown command
      while (!stopCalled) {
        
        // 如果端点被暂停则轮询睡眠
        // Loop if endpoint is paused
        while (endpoint.isPaused() && !stopCalled) {
          state = AcceptorState.PAUSED;
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            // Ignore
          }
        }
        
        // 如果已经停止则退出循环
        if (stopCalled) {
          break;
        }
        // 设置接收状态为运行中
        state = AcceptorState.RUNNING;
        
        try {
          // 如果接收了最大连接，则等待
          //if we have reached max connections, wait
          endpoint.countUpOrAwaitConnection();
          
          // 等待闭锁时，端点可能已经暂停
          // Endpoint might have been paused while waiting for latch
          // If that is the case, don't accept new connections
          if (endpoint.isPaused()) {
            // 退出循
            continue;
          }
          
          U socket = null;
          try {
            // 从服务端套接字接收进来的下一个连接
            // Accept the next incoming connection from the server
            // socket
            socket = endpoint.serverSocketAccept();
          } catch (Exception ioe) {
            // We didn't get a socket
            endpoint.countDownConnection();
            if (endpoint.isRunning()) {
              // Introduce delay if necessary
              errorDelay = handleExceptionWithDelay(errorDelay);
              // re-throw
              throw ioe;
            } else {
              break;
            }
          }
          // Successful accept, reset the error delay
          errorDelay = 0;
          
          // 配置 socket
          // Configure the socket
          if (!stopCalled && !endpoint.isPaused()) {
            // setSocketOptions() will hand the socket off to
            // an appropriate processor if successful
            // 如果 setSocketOptions() 设置成功，则把socket 设置一个适当的处理器
            // 即开始处理客户端的 socket
            if (!endpoint.setSocketOptions(socket)) {
              endpoint.closeSocket(socket);
            }
          } else {
            endpoint.destroySocket(socket);
          }
        } catch (Throwable t) {
          ExceptionUtils.handleThrowable(t);
          String msg = sm.getString("endpoint.accept.fail");
          // APR specific.
          // Could push this down but not sure it is worth the trouble.
          if (t instanceof Error) {
            Error e = (Error) t;
            if (e.getError() == 233) {
              // Not an error on HP-UX so log as a warning
              // so it can be filtered out on that platform
              // See bug 50273
              log.warn(msg, t);
            } else {
              log.error(msg, t);
            }
          } else {
            log.error(msg, t);
          }
        }
      }
    } finally {
      stopLatch.countDown();
    }
    state = AcceptorState.ENDED;
  }
  
  public void stop() {
    stopCalled = true;
    try {
      if (!stopLatch.await(10, TimeUnit.SECONDS)) {
        log.warn(sm.getString("acceptor.stop.fail", getThreadName()));
      }
    } catch (InterruptedException e) {
      log.warn(sm.getString("acceptor.stop.interrupted", getThreadName()), e);
    }
  }
  
  /**
   * Handles exceptions where a delay is required to prevent a Thread from
   * entering a tight loop which will consume CPU and may also trigger large
   * amounts of logging. For example, this can happen if the ulimit for open
   * files is reached.
   *
   * @param currentErrorDelay The current delay being applied on failure
   * @return The delay to apply on the next failure
   */
  protected int handleExceptionWithDelay(int currentErrorDelay) {
    // Don't delay on first exception
    if (currentErrorDelay > 0) {
      try {
        Thread.sleep(currentErrorDelay);
      } catch (InterruptedException e) {
        // Ignore
      }
    }
    
    // On subsequent exceptions, start the delay at 50ms, doubling the delay
    // on every subsequent exception until the delay reaches 1.6 seconds.
    if (currentErrorDelay == 0) {
      return INITIAL_ERROR_DELAY;
    } else if (currentErrorDelay < MAX_ERROR_DELAY) {
      return currentErrorDelay * 2;
    } else {
      return MAX_ERROR_DELAY;
    }
  }
  
  public enum AcceptorState {
    NEW, RUNNING, PAUSED, ENDED
  }
  
}
