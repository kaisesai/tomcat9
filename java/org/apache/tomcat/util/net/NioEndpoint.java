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

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 * <p>
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel, SocketChannel> {
  
  // -------------------------------------------------------------- Constants
  
  public static final int OP_REGISTER = 0x100; //register interest op
  
  private static final Log log = LogFactory.getLog(NioEndpoint.class);
  
  // ----------------------------------------------------------------- Fields
  
  private NioSelectorPool selectorPool = new NioSelectorPool();
  
  /**
   * Server socket "pointer".
   */
  private volatile ServerSocketChannel serverSock = null;
  
  /**
   * Stop latch used to wait for poller stop
   */
  private volatile CountDownLatch stopLatch = null;
  
  /**
   * Cache for poller events
   */
  private SynchronizedStack<PollerEvent> eventCache;
  
  /**
   * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
   */
  private SynchronizedStack<NioChannel> nioChannels;
  
  // ------------------------------------------------------------- Properties
  
  /**
   * Use System.inheritableChannel to obtain channel from stdin/stdout.
   */
  private boolean useInheritedChannel = false;
  
  /**
   * Priority of the poller thread.
   */
  private int pollerThreadPriority = Thread.NORM_PRIORITY;
  
  private long selectorTimeout = 1000;
  
  /**
   * The socket poller.
   */
  private Poller poller = null;
  
  /**
   * Generic properties, introspected
   */
  @Override
  public boolean setProperty(String name, String value) {
    final String selectorPoolName = "selectorPool.";
    try {
      if (name.startsWith(selectorPoolName)) {
        return IntrospectionUtils
          .setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
      } else {
        return super.setProperty(name, value);
      }
    } catch (Exception e) {
      log.error(sm.getString("endpoint.setAttributeError", name, value), e);
      return false;
    }
  }
  
  public boolean getUseInheritedChannel() {
    return useInheritedChannel;
  }
  
  public void setUseInheritedChannel(boolean useInheritedChannel) {
    this.useInheritedChannel = useInheritedChannel;
  }
  
  public int getPollerThreadPriority() {
    return pollerThreadPriority;
  }
  
  public void setPollerThreadPriority(int pollerThreadPriority) {
    this.pollerThreadPriority = pollerThreadPriority;
  }
  
  /**
   * Always returns 1.
   *
   * @return Always 1.
   * @deprecated Will be removed in Tomcat 10.
   */
  @Deprecated
  public int getPollerThreadCount() {
    return 1;
  }
  
  /**
   * NO-OP.
   *
   * @param pollerThreadCount Unused
   * @deprecated Will be removed in Tomcat 10.
   */
  @Deprecated
  public void setPollerThreadCount(int pollerThreadCount) {
  }
  
  public long getSelectorTimeout() {
    return this.selectorTimeout;
  }
  
  public void setSelectorTimeout(long timeout) {
    this.selectorTimeout = timeout;
  }
  
  /**
   * Is deferAccept supported?
   */
  @Override
  public boolean getDeferAccept() {
    // Not supported
    return false;
  }
  
  /**
   * Number of keep-alive sockets.
   *
   * @return The number of sockets currently in the keep-alive state waiting
   * for the next request to be received on the socket
   */
  public int getKeepAliveCount() {
    if (poller == null) {
      return 0;
    } else {
      return poller.getKeyCount();
    }
  }
  
  // --------------------------------------------------------- Public Methods
  
  /**
   * Initialize the endpoint.
   */
  @Override
  public void bind() throws Exception {
    initServerSocket();
    
    setStopLatch(new CountDownLatch(1));
    
    // Initialize SSL if needed
    initialiseSsl();
    
    selectorPool.open(getName());
  }
  
  // ----------------------------------------------- Public Lifecycle Methods
  
  // Separated out to make it easier for folks that extend NioEndpoint to
  // implement custom [server]sockets
  protected void initServerSocket() throws Exception {
    if (!getUseInheritedChannel()) {
      serverSock = ServerSocketChannel.open();
      socketProperties.setProperties(serverSock.socket());
      InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
      serverSock.socket().bind(addr, getAcceptCount());
    } else {
      // Retrieve the channel provided by the OS
      Channel ic = System.inheritedChannel();
      if (ic instanceof ServerSocketChannel) {
        serverSock = (ServerSocketChannel) ic;
      }
      if (serverSock == null) {
        throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
      }
    }
    serverSock.configureBlocking(true); //mimic APR behavior
  }
  
  /**
   * Start the NIO endpoint, creating acceptor, poller threads.
   */
  @Override
  public void startInternal() throws Exception {
    
    if (!running) {
      running = true;
      paused = false;
      
      if (socketProperties.getProcessorCache() != 0) {
        // 创建处理者缓存栈 SocketProcessorBase<S>
        processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                                                 socketProperties.getProcessorCache());
      }
      if (socketProperties.getEventCache() != 0) {
        // 创建事件缓存栈 PollerEvent
        eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                                             socketProperties.getEventCache());
      }
      if (socketProperties.getBufferPool() != 0) {
        // 创建 nio 栈 NioChannel
        nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                                              socketProperties.getBufferPool());
      }
      
      // 创建工作者线程池
      // Create worker collection
      if (getExecutor() == null) {
        createExecutor();
      }
      
      // 初始化连接限流计数器
      initializeConnectionLatch();
      
      // 创建一个轮询器，名称为 ClientPoller，主要负责处理接收客户端 socket 的事件
      // Start poller thread
      poller = new Poller();
      // 客户端轮询器
      Thread pollerThread = new Thread(poller, getName() + "-ClientPoller");
      pollerThread.setPriority(threadPriority);
      // 设置守护线程
      pollerThread.setDaemon(true);
      // 启动任务
      pollerThread.start();
      
      // 创建一个接受者线程
      startAcceptorThread();
    }
  }
  
  /**
   * Stop the endpoint. This will cause all processing threads to stop.
   */
  @Override
  public void stopInternal() {
    if (!paused) {
      pause();
    }
    if (running) {
      running = false;
      acceptor.stop();
      if (poller != null) {
        poller.destroy();
        poller = null;
      }
      try {
        if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
          log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
        }
      } catch (InterruptedException e) {
        log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
      }
      shutdownExecutor();
      if (eventCache != null) {
        eventCache.clear();
        eventCache = null;
      }
      if (nioChannels != null) {
        nioChannels.clear();
        nioChannels = null;
      }
      if (processorCache != null) {
        processorCache.clear();
        processorCache = null;
      }
    }
  }
  
  /**
   * Deallocate NIO memory pools, and close server socket.
   */
  @Override
  public void unbind() throws Exception {
    if (log.isDebugEnabled()) {
      log
        .debug("Destroy initiated for " + new InetSocketAddress(getAddress(), getPortWithOffset()));
    }
    if (running) {
      stop();
    }
    try {
      doCloseServerSocket();
    } catch (IOException ioe) {
      getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
    }
    destroySsl();
    super.unbind();
    if (getHandler() != null) {
      getHandler().recycle();
    }
    selectorPool.close();
    if (log.isDebugEnabled()) {
      log
        .debug("Destroy completed for " + new InetSocketAddress(getAddress(), getPortWithOffset()));
    }
  }
  
  @Override
  protected void doCloseServerSocket() throws IOException {
    if (!getUseInheritedChannel() && serverSock != null) {
      // Close server socket
      serverSock.close();
    }
    serverSock = null;
  }
  
  protected NioSelectorPool getSelectorPool() {
    return selectorPool;
  }
  
  // ------------------------------------------------------ Protected Methods
  
  public void setSelectorPool(NioSelectorPool selectorPool) {
    this.selectorPool = selectorPool;
  }
  
  protected SynchronizedStack<NioChannel> getNioChannels() {
    return nioChannels;
  }
  
  protected Poller getPoller() {
    return poller;
  }
  
  protected CountDownLatch getStopLatch() {
    return stopLatch;
  }
  
  protected void setStopLatch(CountDownLatch stopLatch) {
    this.stopLatch = stopLatch;
  }
  
  /**
   * Process the specified connection.
   *
   * @param socket The socket channel
   * @return <code>true</code> if the socket was correctly configured
   * and processing may continue, <code>false</code> if the socket needs to be
   * close immediately
   */
  @Override
  protected boolean setSocketOptions(SocketChannel socket) {
    NioSocketWrapper socketWrapper = null;
    try {
      // 分配 channel 和包装器
      // Allocate channel and wrapper
      NioChannel channel = null;
      if (nioChannels != null) {
        channel = nioChannels.pop();
      }
      if (channel == null) {
        // 创建一个 socket 缓存处理器，
        SocketBufferHandler bufhandler = new SocketBufferHandler(
          socketProperties.getAppReadBufSize(), socketProperties.getAppWriteBufSize(),
          socketProperties.getDirectBuffer());
        if (isSSLEnabled()) {
          // ssl 安全的 niochannel
          channel = new SecureNioChannel(bufhandler, selectorPool, this);
        } else {
          // 普通的 niochannel
          channel = new NioChannel(bufhandler);
        }
      }
      // 创建一个 channel 包装器
      NioSocketWrapper newWrapper = new NioSocketWrapper(channel, this);
      // 重置 channel
      channel.reset(socket, newWrapper);
      // 把 socket 与包装器进行映射
      connections.put(socket, newWrapper);
      socketWrapper = newWrapper;
      
      // 设置 socket 的属性，不允许阻塞，让它被轮询的使用
      // Set socket properties
      // Disable blocking, polling will be used
      socket.configureBlocking(false);
      socketProperties.setProperties(socket.socket());
      
      // 设置 socket 包装器的属性
      socketWrapper.setReadTimeout(getConnectionTimeout());
      socketWrapper.setWriteTimeout(getConnectionTimeout());
      socketWrapper.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
      
      // 把 channel 和包装器注册到轮询器中
      poller.register(channel, socketWrapper);
      return true;
    } catch (Throwable t) {
      ExceptionUtils.handleThrowable(t);
      try {
        log.error(sm.getString("endpoint.socketOptionsError"), t);
      } catch (Throwable tt) {
        ExceptionUtils.handleThrowable(tt);
      }
      if (socketWrapper == null) {
        destroySocket(socket);
      }
    }
    // Tell to close the socket if needed
    return false;
  }
  
  @Override
  protected void destroySocket(SocketChannel socket) {
    countDownConnection();
    try {
      socket.close();
    } catch (IOException ioe) {
      if (log.isDebugEnabled()) {
        log.debug(sm.getString("endpoint.err.close"), ioe);
      }
    }
  }
  
  @Override
  protected NetworkChannel getServerSocket() {
    return serverSock;
  }
  
  @Override
  protected SocketChannel serverSocketAccept() throws Exception {
    return serverSock.accept();
  }
  
  @Override
  protected Log getLog() {
    return log;
  }
  
  /**
   * 创建一个 socket 处理器
   *
   * @param socketWrapper
   * @param event
   * @return
   */
  @Override
  protected SocketProcessorBase<NioChannel> createSocketProcessor(
    SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
    return new SocketProcessor(socketWrapper, event);
  }
  
  // ----------------------------------------------------- Poller Inner Classes
  
  /**
   * PollerEvent, cacheable object for poller events to avoid GC
   */
  public static class PollerEvent {
    
    private NioChannel socket;
    
    private int interestOps;
    
    public PollerEvent(NioChannel ch, int intOps) {
      reset(ch, intOps);
    }
    
    public void reset(NioChannel ch, int intOps) {
      socket = ch;
      interestOps = intOps;
    }
    
    public NioChannel getSocket() {
      return socket;
    }
    
    public int getInterestOps() {
      return interestOps;
    }
    
    public void reset() {
      reset(null, 0);
    }
    
    @Override
    public String toString() {
      return "Poller event: socket [" + socket + "], socketWrapper [" + socket.getSocketWrapper()
        + "], interestOps [" + interestOps + "]";
    }
    
  }
  
  public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {
    
    private final NioSelectorPool pool;
    
    private final SynchronizedStack<NioChannel> nioChannels;
    
    private final Poller poller;
    
    private int interestOps = 0;
    
    private CountDownLatch readLatch = null;
    
    private CountDownLatch writeLatch = null;
    
    private volatile SendfileData sendfileData = null;
    
    private volatile long lastRead = System.currentTimeMillis();
    
    private volatile long lastWrite = lastRead;
    
    public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
      super(channel, endpoint);
      // 选择器池
      pool = endpoint.getSelectorPool();
      // niochannel 栈
      nioChannels = endpoint.getNioChannels();
      // 轮询器
      poller = endpoint.getPoller();
      // socket 缓存处理器
      socketBufferHandler = channel.getBufHandler();
    }
    
    public Poller getPoller() {
      return poller;
    }
    
    public int interestOps() {
      return interestOps;
    }
    
    public int interestOps(int ops) {
      this.interestOps = ops;
      return ops;
    }
    
    public CountDownLatch getReadLatch() {
      return readLatch;
    }
    
    public CountDownLatch getWriteLatch() {
      return writeLatch;
    }
    
    protected CountDownLatch resetLatch(CountDownLatch latch) {
      if (latch == null || latch.getCount() == 0) {
        return null;
      } else {
        throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
      }
    }
    
    public void resetReadLatch() {
      readLatch = resetLatch(readLatch);
    }
    
    public void resetWriteLatch() {
      writeLatch = resetLatch(writeLatch);
    }
    
    protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
      if (latch == null || latch.getCount() == 0) {
        return new CountDownLatch(cnt);
      } else {
        throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
      }
    }
    
    public void startReadLatch(int cnt) {
      readLatch = startLatch(readLatch, cnt);
    }
    
    public void startWriteLatch(int cnt) {
      writeLatch = startLatch(writeLatch, cnt);
    }
    
    protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit)
      throws InterruptedException {
      if (latch == null) {
        throw new IllegalStateException(sm.getString("endpoint.nio.nullLatch"));
      }
      // Note: While the return value is ignored if the latch does time
      //       out, logic further up the call stack will trigger a
      //       SocketTimeoutException
      latch.await(timeout, unit);
    }
    
    public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException {
      awaitLatch(readLatch, timeout, unit);
    }
    
    public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException {
      awaitLatch(writeLatch, timeout, unit);
    }
    
    public SendfileData getSendfileData() {
      return this.sendfileData;
    }
    
    public void setSendfileData(SendfileData sf) {
      this.sendfileData = sf;
    }
    
    public void updateLastWrite() {
      lastWrite = System.currentTimeMillis();
    }
    
    public long getLastWrite() {
      return lastWrite;
    }
    
    public void updateLastRead() {
      lastRead = System.currentTimeMillis();
    }
    
    public long getLastRead() {
      return lastRead;
    }
    
    @Override
    public boolean isReadyForRead() throws IOException {
      socketBufferHandler.configureReadBufferForRead();
      
      if (socketBufferHandler.getReadBuffer().remaining() > 0) {
        return true;
      }
      
      fillReadBuffer(false);
      
      boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
      return isReady;
    }
    
    @Override
    public int read(boolean block, byte[] b, int off, int len) throws IOException {
      int nRead = populateReadBuffer(b, off, len);
      if (nRead > 0) {
        return nRead;
        /*
         * Since more bytes may have arrived since the buffer was last
         * filled, it is an option at this point to perform a
         * non-blocking read. However correctly handling the case if
         * that read returns end of stream adds complexity. Therefore,
         * at the moment, the preference is for simplicity.
         */
      }
  
      // 读取数据
      // Fill the read buffer as best we can.
      nRead = fillReadBuffer(block);
      updateLastRead();
      
      // Fill as much of the remaining byte array as possible with the
      // data that was just read
      if (nRead > 0) {
        socketBufferHandler.configureReadBufferForRead();
        nRead = Math.min(nRead, len);
        socketBufferHandler.getReadBuffer().get(b, off, nRead);
      }
      return nRead;
    }
    
    @Override
    public int read(boolean block, ByteBuffer to) throws IOException {
      int nRead = populateReadBuffer(to);
      if (nRead > 0) {
        return nRead;
        /*
         * Since more bytes may have arrived since the buffer was last
         * filled, it is an option at this point to perform a
         * non-blocking read. However correctly handling the case if
         * that read returns end of stream adds complexity. Therefore,
         * at the moment, the preference is for simplicity.
         */
      }
      
      // The socket read buffer capacity is socket.appReadBufSize
      int limit = socketBufferHandler.getReadBuffer().capacity();
      if (to.remaining() >= limit) {
        to.limit(to.position() + limit);
        // 开始读取 socket 数据，放到 byteBuffer
        nRead = fillReadBuffer(block, to);
        if (log.isDebugEnabled()) {
          log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
        }
        updateLastRead();
      } else {
        // Fill the read buffer as best we can.
        // 读取数据到缓存区
        nRead = fillReadBuffer(block);
        if (log.isDebugEnabled()) {
          log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
        }
        updateLastRead();
  
        // Fill as much of the remaining byte array as possible with the data that was just read
        //
        if (nRead > 0) {
          // 用刚读取的数据尽可能多的填充剩余字节数组
          nRead = populateReadBuffer(to);
        }
      }
      return nRead;
    }
    
    @Override
    protected void doClose() {
      if (log.isDebugEnabled()) {
        log.debug("Calling [" + getEndpoint() + "].closeSocket([" + this + "])");
      }
      try {
        getEndpoint().connections.remove(getSocket().getIOChannel());
        if (getSocket().isOpen()) {
          getSocket().close(true);
        }
        if (getEndpoint().running && !getEndpoint().paused) {
          if (nioChannels == null || !nioChannels.push(getSocket())) {
            getSocket().free();
          }
        }
      } catch (Throwable e) {
        ExceptionUtils.handleThrowable(e);
        if (log.isDebugEnabled()) {
          log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
        }
      } finally {
        socketBufferHandler = SocketBufferHandler.EMPTY;
        nonBlockingWriteBuffer.clear();
        reset(NioChannel.CLOSED_NIO_CHANNEL);
      }
      try {
        SendfileData data = getSendfileData();
        if (data != null && data.fchannel != null && data.fchannel.isOpen()) {
          data.fchannel.close();
        }
      } catch (Throwable e) {
        ExceptionUtils.handleThrowable(e);
        if (log.isDebugEnabled()) {
          log.error(sm.getString("endpoint.sendfile.closeError"), e);
        }
      }
    }
    
    private int fillReadBuffer(boolean block) throws IOException {
      socketBufferHandler.configureReadBufferForWrite();
      return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
    }
    
    private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
      int nRead;
      // 获取 socket
      NioChannel socket = getSocket();
      if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
        throw new ClosedChannelException();
      }
      if (block) {
        Selector selector = null;
        try {
          selector = pool.get();
        } catch (IOException x) {
          // Ignore
        }
        try {
          // NioSelectorPool 读取数据
          nRead = pool.read(to, socket, selector, getReadTimeout());
        } finally {
          if (selector != null) {
            pool.put(selector);
          }
        }
      } else {
        // 非阻塞的，直接从 socket 读取数据到缓存区 byteBuffer
        nRead = socket.read(to);
        if (nRead == -1) {
          throw new EOFException();
        }
      }
      return nRead;
    }
    
    @Override
    protected void doWrite(boolean block, ByteBuffer from) throws IOException {
      NioChannel socket = getSocket();
      if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
        throw new ClosedChannelException();
      }
      if (block) {
        long writeTimeout = getWriteTimeout();
        Selector selector = null;
        try {
          selector = pool.get();
        } catch (IOException x) {
          // Ignore
        }
        try {
          pool.write(from, socket, selector, writeTimeout);
          // Make sure we are flushed
          do {
          }
          while (!socket.flush(true, selector, writeTimeout));
        } finally {
          if (selector != null) {
            pool.put(selector);
          }
        }
        // If there is data left in the buffer the socket will be registered for
        // write further up the stack. This is to ensure the socket is only
        // registered for write once as both container and user code can trigger
        // write registration.
      } else {
        int n = 0;
        do {
          n = socket.write(from);
          if (n == -1) {
            throw new EOFException();
          }
        }
        while (n > 0 && from.hasRemaining());
      }
      updateLastWrite();
    }
    
    @Override
    public void registerReadInterest() {
      if (log.isDebugEnabled()) {
        log.debug(sm.getString("endpoint.debug.registerRead", this));
      }
      getPoller().add(this, SelectionKey.OP_READ);
    }
    
    @Override
    public void registerWriteInterest() {
      if (log.isDebugEnabled()) {
        log.debug(sm.getString("endpoint.debug.registerWrite", this));
      }
      getPoller().add(this, SelectionKey.OP_WRITE);
    }
    
    @Override
    public SendfileDataBase createSendfileData(String filename, long pos, long length) {
      return new SendfileData(filename, pos, length);
    }
    
    @Override
    public SendfileState processSendfile(SendfileDataBase sendfileData) {
      setSendfileData((SendfileData) sendfileData);
      SelectionKey key = getSocket().getIOChannel().keyFor(getPoller().getSelector());
      // Might as well do the first write on this thread
      return getPoller().processSendfile(key, this, true);
    }
    
    @Override
    protected void populateRemoteAddr() {
      SocketChannel sc = getSocket().getIOChannel();
      if (sc != null) {
        InetAddress inetAddr = sc.socket().getInetAddress();
        if (inetAddr != null) {
          remoteAddr = inetAddr.getHostAddress();
        }
      }
    }
    
    @Override
    protected void populateRemoteHost() {
      SocketChannel sc = getSocket().getIOChannel();
      if (sc != null) {
        InetAddress inetAddr = sc.socket().getInetAddress();
        if (inetAddr != null) {
          remoteHost = inetAddr.getHostName();
          if (remoteAddr == null) {
            remoteAddr = inetAddr.getHostAddress();
          }
        }
      }
    }
    
    @Override
    protected void populateRemotePort() {
      SocketChannel sc = getSocket().getIOChannel();
      if (sc != null) {
        remotePort = sc.socket().getPort();
      }
    }
    
    @Override
    protected void populateLocalName() {
      SocketChannel sc = getSocket().getIOChannel();
      if (sc != null) {
        InetAddress inetAddr = sc.socket().getLocalAddress();
        if (inetAddr != null) {
          localName = inetAddr.getHostName();
        }
      }
    }
    
    @Override
    protected void populateLocalAddr() {
      SocketChannel sc = getSocket().getIOChannel();
      if (sc != null) {
        InetAddress inetAddr = sc.socket().getLocalAddress();
        if (inetAddr != null) {
          localAddr = inetAddr.getHostAddress();
        }
      }
    }
    
    @Override
    protected void populateLocalPort() {
      SocketChannel sc = getSocket().getIOChannel();
      if (sc != null) {
        localPort = sc.socket().getLocalPort();
      }
    }
    
    /**
     * {@inheritDoc}
     *
     * @param clientCertProvider Ignored for this implementation
     */
    @Override
    public SSLSupport getSslSupport(String clientCertProvider) {
      if (getSocket() instanceof SecureNioChannel) {
        SecureNioChannel ch = (SecureNioChannel) getSocket();
        SSLEngine sslEngine = ch.getSslEngine();
        if (sslEngine != null) {
          SSLSession session = sslEngine.getSession();
          return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
        }
      }
      return null;
    }
    
    @Override
    public void doClientAuth(SSLSupport sslSupport) throws IOException {
      SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
      SSLEngine engine = sslChannel.getSslEngine();
      if (!engine.getNeedClientAuth()) {
        // Need to re-negotiate SSL connection
        engine.setNeedClientAuth(true);
        sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
        ((JSSESupport) sslSupport).setSession(engine.getSession());
      }
    }
    
    @Override
    public void setAppReadBufHandler(ApplicationBufferHandler handler) {
      getSocket().setAppReadBufHandler(handler);
    }
    
    @Override
    protected <A> OperationState<A> newOperationState(boolean read, ByteBuffer[] buffers,
                                                      int offset, int length, BlockingMode block,
                                                      long timeout, TimeUnit unit, A attachment,
                                                      CompletionCheck check,
                                                      CompletionHandler<Long, ? super A> handler,
                                                      Semaphore semaphore,
                                                      VectoredIOCompletionHandler<A> completion) {
      return new NioOperationState<>(read, buffers, offset, length, block, timeout, unit,
                                     attachment, check, handler, semaphore, completion);
    }
    
    private class NioOperationState<A> extends OperationState<A> {
      
      private volatile boolean inline = true;
      
      private NioOperationState(boolean read, ByteBuffer[] buffers, int offset, int length,
                                BlockingMode block, long timeout, TimeUnit unit, A attachment,
                                CompletionCheck check, CompletionHandler<Long, ? super A> handler,
                                Semaphore semaphore, VectoredIOCompletionHandler<A> completion) {
        super(read, buffers, offset, length, block, timeout, unit, attachment, check, handler,
              semaphore, completion);
      }
      
      @Override
      protected boolean isInline() {
        return inline;
      }
      
      @Override
      public void run() {
        // Perform the IO operation
        // Called from the poller to continue the IO operation
        long nBytes = 0;
        if (getError() == null) {
          try {
            synchronized (this) {
              if (!completionDone) {
                // This filters out same notification until processing
                // of the current one is done
                if (log.isDebugEnabled()) {
                  log.debug("Skip concurrent " + (read ? "read" : "write") + " notification");
                }
                return;
              }
              if (read) {
                // Read from main buffer first
                if (!socketBufferHandler.isReadBufferEmpty()) {
                  // There is still data inside the main read buffer, it needs to be read first
                  socketBufferHandler.configureReadBufferForRead();
                  for (int i = 0; i < length && !socketBufferHandler.isReadBufferEmpty(); i++) {
                    nBytes += transfer(socketBufferHandler.getReadBuffer(), buffers[offset + i]);
                  }
                }
                if (nBytes == 0) {
                  nBytes = getSocket().read(buffers, offset, length);
                  updateLastRead();
                }
              } else {
                boolean doWrite = true;
                // Write from main buffer first
                if (!socketBufferHandler.isWriteBufferEmpty()) {
                  // There is still data inside the main write buffer, it needs to be written first
                  socketBufferHandler.configureWriteBufferForRead();
                  do {
                    nBytes = getSocket().write(socketBufferHandler.getWriteBuffer());
                  }
                  while (!socketBufferHandler.isWriteBufferEmpty() && nBytes > 0);
                  if (!socketBufferHandler.isWriteBufferEmpty()) {
                    doWrite = false;
                  }
                  // Preserve a negative value since it is an error
                  if (nBytes > 0) {
                    nBytes = 0;
                  }
                }
                if (doWrite) {
                  long n = 0;
                  do {
                    n = getSocket().write(buffers, offset, length);
                    if (n == -1) {
                      nBytes = n;
                    } else {
                      nBytes += n;
                    }
                  }
                  while (n > 0);
                  updateLastWrite();
                }
              }
              if (nBytes != 0 || !buffersArrayHasRemaining(buffers, offset, length)) {
                completionDone = false;
              }
            }
          } catch (IOException e) {
            setError(e);
          }
        }
        if (nBytes > 0 || (nBytes == 0 && !buffersArrayHasRemaining(buffers, offset, length))) {
          // The bytes processed are only updated in the completion handler
          completion.completed(Long.valueOf(nBytes), this);
        } else if (nBytes < 0 || getError() != null) {
          IOException error = getError();
          if (error == null) {
            error = new EOFException();
          }
          completion.failed(error, this);
        } else {
          // As soon as the operation uses the poller, it is no longer inline
          inline = false;
          if (read) {
            registerReadInterest();
          } else {
            registerWriteInterest();
          }
        }
      }
      
    }
    
  }
  
  // --------------------------------------------------- Socket Wrapper Class
  
  /**
   * SendfileData class.
   */
  public static class SendfileData extends SendfileDataBase {
    
    protected volatile FileChannel fchannel;
    
    public SendfileData(String filename, long pos, long length) {
      super(filename, pos, length);
    }
    
  }
  
  // ---------------------------------------------- SocketProcessor Inner Class
  
  /**
   * 轮询器
   * Poller class.
   */
  public class Poller implements Runnable {
    
    /**
     * 事件同步队列
     */
    private final SynchronizedQueue<PollerEvent> events = new SynchronizedQueue<>();
    
    /**
     * 选择器
     */
    private Selector selector;
    
    private volatile boolean close = false;
    
    // Optimize expiration handling
    private long nextExpiration = 0;
    
    private AtomicLong wakeupCounter = new AtomicLong(0);
    
    private volatile int keyCount = 0;
    
    public Poller() throws IOException {
      // 打开一个选择器
      this.selector = Selector.open();
    }
    
    public int getKeyCount() {
      return keyCount;
    }
    
    /**
     * @return 选择器，用来选择 key
     */
    public Selector getSelector() {
      return selector;
    }
    
    /**
     * Destroy the poller.
     */
    protected void destroy() {
      // Wait for polltime before doing anything, so that the poller threads
      // exit, otherwise parallel closure of sockets which are still
      // in the poller can cause problems
      close = true;
      selector.wakeup();
    }
    
    /**
     * 添加一个事件
     *
     * @param event
     */
    private void addEvent(PollerEvent event) {
      // 把事件添加到队列
      events.offer(event);
      if (wakeupCounter.incrementAndGet() == 0) {
        // 唤醒选择器
        selector.wakeup();
      }
    }
    
    /**
     * 添加指定的 socket 和关联的池添加到事件队列。
     * <p>
     * Add specified socket and associated pool to the poller. The socket will
     * be added to a temporary array, and polled first after a maximum amount
     * of time equal to pollTime (in most cases, latency will be much lower,
     * however).
     *
     * @param socketWrapper to add to the poller
     * @param interestOps   Operations for which to register this socket with
     *                      the Poller
     */
    public void add(NioSocketWrapper socketWrapper, int interestOps) {
      PollerEvent r = null;
      if (eventCache != null) {
        r = eventCache.pop();
      }
      if (r == null) {
        r = new PollerEvent(socketWrapper.getSocket(), interestOps);
      } else {
        r.reset(socketWrapper.getSocket(), interestOps);
      }
      // 添加事件到队列
      addEvent(r);
      // 如果轮询已经被关闭，则执行停止事件
      if (close) {
        processSocket(socketWrapper, SocketEvent.STOP, false);
      }
    }
    
    /**
     * 处理事件队列中的事件。
     * <p>
     * Processes events in the event queue of the Poller.
     *
     * @return <code>true</code> if some events were processed,
     * <code>false</code> if queue was empty，返回 true，表示一些事件已经被处理，false 表示队列为空。
     */
    public boolean events() {
      boolean result = false;
      
      PollerEvent pe = null;
      // 不断从事件队列中弹出事件，进行处理
      for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++) {
        result = true;
        // 获取 NioChannel 通道
        NioChannel channel = pe.getSocket();
        // 获取原生 socketChannel
        SocketChannel sc = channel.getIOChannel();
        // 获取 socket 包装器
        NioSocketWrapper socketWrapper = channel.getSocketWrapper();
        // 获取事件操作选项
        int interestOps = pe.getInterestOps();
        if (sc == null) {
          log.warn(sm.getString("endpoint.nio.nullSocketChannel"));
          if (socketWrapper != null) {
            socketWrapper.close();
          }
        } else if (interestOps == OP_REGISTER) {
          // 注册操作
          try {
            // 注册一个选择器 key，
            sc.register(getSelector(), SelectionKey.OP_READ, socketWrapper);
          } catch (Exception x) {
            log.error(sm.getString("endpoint.nio.registerFail"), x);
          }
        } else {
          // 其他操作
          // 从 socketChannel 中获取选择器 key
          final SelectionKey key = sc.keyFor(getSelector());
          if (key == null) {
            // The key was cancelled (e.g. due to socket closure)
            // and removed from the selector while it was being
            // processed. Count down the connections at this point
            // since it won't have been counted down when the socket
            // closed.
            socketWrapper.close();
          } else {
            // 从选择器 key 中获取 socket 包装器
            final NioSocketWrapper attachment = (NioSocketWrapper) key.attachment();
            if (attachment != null) {
              // We are registering the key to start with, reset the fairness counter.
              try {
                // 获取 key 操作选项
                int ops = key.interestOps() | interestOps;
                // 重新设置 socket 包装器的操作选项
                attachment.interestOps(ops);
                // 重新设置选择器 key 的操作选项
                key.interestOps(ops);
              } catch (CancelledKeyException ckx) {
                cancelledKey(key, socketWrapper);
              }
            } else {
              cancelledKey(key, socketWrapper);
            }
          }
        }
        if (running && !paused && eventCache != null) {
          // 把事件重置，推送到事件缓存队列中
          pe.reset();
          eventCache.push(pe);
        }
      }
      
      return result;
    }
    
    /**
     * 向轮询器中注册一个新的 socket。
     * Registers a newly created socket with the poller.
     *
     * @param socket        The newly created socket
     * @param socketWrapper The socket wrapper
     */
    public void register(final NioChannel socket, final NioSocketWrapper socketWrapper) {
      // socket 包装器设置操作选项：OP_READ
      socketWrapper.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
      // 获取或者创建一个轮询事件
      PollerEvent event = null;
      if (eventCache != null) {
        event = eventCache.pop();
      }
      // 创建或者设置一个轮询注册事件
      if (event == null) {
        event = new PollerEvent(socket, OP_REGISTER);
      } else {
        event.reset(socket, OP_REGISTER);
      }
      // 添加到事件队列中
      addEvent(event);
    }
    
    public void cancelledKey(SelectionKey sk, SocketWrapperBase<NioChannel> socketWrapper) {
      try {
        // If is important to cancel the key first, otherwise a deadlock may occur between the
        // poller select and the socket channel close which would cancel the key
        if (sk != null) {
          sk.attach(null);
          if (sk.isValid()) {
            sk.cancel();
          }
        }
      } catch (Throwable e) {
        ExceptionUtils.handleThrowable(e);
        if (log.isDebugEnabled()) {
          log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
        }
      } finally {
        if (socketWrapper != null) {
          socketWrapper.close();
        }
      }
    }
    
    /**
     * The background thread that adds sockets to the Poller, checks the
     * poller for triggered events and hands the associated socket off to an
     * appropriate processor as events occur.
     */
    @Override
    public void run() {
      // 不断轮询，直到被销毁
      // Loop until destroy() is called
      while (true) {
        
        boolean hasEvents = false;
        
        try {
          if (!close) {
            // 处理事件，注册 socket 选择器 key
            hasEvents = events();
            if (wakeupCounter.getAndSet(-1) > 0) {
              // If we are here, means we have other stuff to do
              // Do a non blocking select
              // 执行非阻塞的选择
              keyCount = selector.selectNow();
            } else {
              // 阻塞的选择
              keyCount = selector.select(selectorTimeout);
            }
            wakeupCounter.set(0);
          }
          // 已经关闭，则关闭选择器
          if (close) {
            events();
            timeout(0, false);
            try {
              selector.close();
            } catch (IOException ioe) {
              log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
            }
            break;
          }
          // Either we timed out or we woke up, process events first
          if (keyCount == 0) {
            hasEvents = (hasEvents | events());
          }
        } catch (Throwable x) {
          ExceptionUtils.handleThrowable(x);
          log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
          continue;
        }
        
        // 获取选择器的所有的 key，并挨个处理
        Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
        // Walk through the collection of ready keys and dispatch
        // any active event.
        while (iterator != null && iterator.hasNext()) {
          // 获取选择器 key
          SelectionKey sk = iterator.next();
          // 移除 key
          iterator.remove();
          // 获取 socket 包装器
          NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
          // Attachment may be null if another thread has called
          // cancelledKey()
          if (socketWrapper != null) {
            // 处理 socket key
            processKey(sk, socketWrapper);
          }
        }
        
        // 处理超时
        // Process timeouts
        timeout(keyCount, hasEvents);
      }
      
      getStopLatch().countDown();
    }
    
    /**
     * 处理 socket
     *
     * @param sk
     * @param socketWrapper
     */
    protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
      try {
        // 如果已经关闭则取消 key
        if (close) {
          cancelledKey(sk, socketWrapper);
        } else if (sk.isValid() && socketWrapper != null) {
          // key 有效，并且 socket 包装器不为空
          if (sk.isReadable() || sk.isWritable()) {
            if (socketWrapper.getSendfileData() != null) {
              // 处理发送文件
              processSendfile(sk, socketWrapper, false);
            } else {
              // 取消注册 key
              unreg(sk, socketWrapper, sk.readyOps());
              boolean closeSocket = false;
              // Read goes before write
              // 先读取
              if (sk.isReadable()) {
                if (socketWrapper.readOperation != null) {
                  if (!socketWrapper.readOperation.process()) {
                    closeSocket = true;
                  }
                  // 处理 socket 的读取事件
                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                  closeSocket = true;
                }
              }
              // 再写入
              if (!closeSocket && sk.isWritable()) {
                if (socketWrapper.writeOperation != null) {
                  if (!socketWrapper.writeOperation.process()) {
                    closeSocket = true;
                  }
                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true)) {
                  closeSocket = true;
                }
              }
              if (closeSocket) {
                cancelledKey(sk, socketWrapper);
              }
            }
          }
        } else {
          // Invalid key
          cancelledKey(sk, socketWrapper);
        }
      } catch (CancelledKeyException ckx) {
        cancelledKey(sk, socketWrapper);
      } catch (Throwable t) {
        ExceptionUtils.handleThrowable(t);
        log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
      }
    }
    
    public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                                         boolean calledByProcessor) {
      NioChannel sc = null;
      try {
        unreg(sk, socketWrapper, sk.readyOps());
        SendfileData sd = socketWrapper.getSendfileData();
        
        if (log.isTraceEnabled()) {
          log.trace("Processing send file for: " + sd.fileName);
        }
        
        if (sd.fchannel == null) {
          // Setup the file channel
          File f = new File(sd.fileName);
          @SuppressWarnings("resource") // Closed when channel is closed
          FileInputStream fis = new FileInputStream(f);
          sd.fchannel = fis.getChannel();
        }
        
        // Configure output channel
        sc = socketWrapper.getSocket();
        // TLS/SSL channel is slightly different
        WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());
        
        // We still have data in the buffer
        if (sc.getOutboundRemaining() > 0) {
          if (sc.flushOutbound()) {
            socketWrapper.updateLastWrite();
          }
        } else {
          long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
          if (written > 0) {
            sd.pos += written;
            sd.length -= written;
            socketWrapper.updateLastWrite();
          } else {
            // Unusual not to be able to transfer any bytes
            // Check the length was set correctly
            if (sd.fchannel.size() <= sd.pos) {
              throw new IOException(sm.getString("endpoint.sendfile.tooMuchData"));
            }
          }
        }
        if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
          if (log.isDebugEnabled()) {
            log.debug("Send file complete for: " + sd.fileName);
          }
          socketWrapper.setSendfileData(null);
          try {
            sd.fchannel.close();
          } catch (Exception ignore) {
          }
          // For calls from outside the Poller, the caller is
          // responsible for registering the socket for the
          // appropriate event(s) if sendfile completes.
          if (!calledByProcessor) {
            switch (sd.keepAliveState) {
              case NONE: {
                if (log.isDebugEnabled()) {
                  log.debug("Send file connection is being closed");
                }
                poller.cancelledKey(sk, socketWrapper);
                break;
              }
              case PIPELINED: {
                if (log.isDebugEnabled()) {
                  log.debug("Connection is keep alive, processing pipe-lined data");
                }
                if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                  poller.cancelledKey(sk, socketWrapper);
                }
                break;
              }
              case OPEN: {
                if (log.isDebugEnabled()) {
                  log.debug("Connection is keep alive, registering back for OP_READ");
                }
                reg(sk, socketWrapper, SelectionKey.OP_READ);
                break;
              }
            }
          }
          return SendfileState.DONE;
        } else {
          if (log.isDebugEnabled()) {
            log.debug("OP_WRITE for sendfile: " + sd.fileName);
          }
          if (calledByProcessor) {
            add(socketWrapper, SelectionKey.OP_WRITE);
          } else {
            reg(sk, socketWrapper, SelectionKey.OP_WRITE);
          }
          return SendfileState.PENDING;
        }
      } catch (IOException e) {
        if (log.isDebugEnabled()) {
          log.debug("Unable to complete sendfile request:", e);
        }
        if (!calledByProcessor && sc != null) {
          poller.cancelledKey(sk, socketWrapper);
        }
        return SendfileState.ERROR;
      } catch (Throwable t) {
        log.error(sm.getString("endpoint.sendfile.error"), t);
        if (!calledByProcessor && sc != null) {
          poller.cancelledKey(sk, socketWrapper);
        }
        return SendfileState.ERROR;
      }
    }
    
    protected void unreg(SelectionKey sk, NioSocketWrapper socketWrapper, int readyOps) {
      // This is a must, so that we don't have multiple threads messing with the socket
      // 取消注册，实际为更改选择器 key 和 socket 包装器的操作选项，目的是为了不让其他线程操作
      reg(sk, socketWrapper, sk.interestOps() & (~readyOps));
    }
    
    protected void reg(SelectionKey sk, NioSocketWrapper socketWrapper, int intops) {
      sk.interestOps(intops);
      socketWrapper.interestOps(intops);
    }
    
    protected void timeout(int keyCount, boolean hasEvents) {
      long now = System.currentTimeMillis();
      // This method is called on every loop of the Poller. Don't process
      // timeouts on every loop of the Poller since that would create too
      // much load and timeouts can afford to wait a few seconds.
      // However, do process timeouts if any of the following are true:
      // - the selector simply timed out (suggests there isn't much load)
      // - the nextExpiration time has passed
      // - the server socket is being closed
      if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
        return;
      }
      int keycount = 0;
      try {
        for (SelectionKey key : selector.keys()) {
          keycount++;
          NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
          try {
            if (socketWrapper == null) {
              // We don't support any keys without attachments
              cancelledKey(key, null);
            } else if (close) {
              key.interestOps(0);
              // Avoid duplicate stop calls
              socketWrapper.interestOps(0);
              cancelledKey(key, socketWrapper);
            } else if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ
              || (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
              boolean readTimeout = false;
              boolean writeTimeout = false;
              // Check for read timeout
              if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                long delta = now - socketWrapper.getLastRead();
                long timeout = socketWrapper.getReadTimeout();
                if (timeout > 0 && delta > timeout) {
                  readTimeout = true;
                }
              }
              // Check for write timeout
              if (!readTimeout
                && (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                long delta = now - socketWrapper.getLastWrite();
                long timeout = socketWrapper.getWriteTimeout();
                if (timeout > 0 && delta > timeout) {
                  writeTimeout = true;
                }
              }
              if (readTimeout || writeTimeout) {
                key.interestOps(0);
                // Avoid duplicate timeout calls
                socketWrapper.interestOps(0);
                socketWrapper.setError(new SocketTimeoutException());
                if (readTimeout && socketWrapper.readOperation != null) {
                  if (!socketWrapper.readOperation.process()) {
                    cancelledKey(key, socketWrapper);
                  }
                } else if (writeTimeout && socketWrapper.writeOperation != null) {
                  if (!socketWrapper.writeOperation.process()) {
                    cancelledKey(key, socketWrapper);
                  }
                } else if (!processSocket(socketWrapper, SocketEvent.ERROR, true)) {
                  cancelledKey(key, socketWrapper);
                }
              }
            }
          } catch (CancelledKeyException ckx) {
            cancelledKey(key, socketWrapper);
          }
        }
      } catch (ConcurrentModificationException cme) {
        // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
        log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
      }
      // For logging purposes only
      long prevExp = nextExpiration;
      nextExpiration = System.currentTimeMillis() + socketProperties.getTimeoutInterval();
      if (log.isTraceEnabled()) {
        log.trace(
          "timeout completed: keys processed=" + keycount + "; now=" + now + "; nextExpiration="
            + prevExp + "; keyCount=" + keyCount + "; hasEvents=" + hasEvents + "; eval=" + (
            (now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
      }
      
    }
    
  }
  
  // ----------------------------------------------- SendfileData Inner Class
  
  /**
   * socket 处理器
   * <p>
   * This class is the equivalent of the Worker, but will simply use in an
   * external Executor thread pool.
   */
  protected class SocketProcessor extends SocketProcessorBase<NioChannel> {
    
    public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
      super(socketWrapper, event);
    }
    
    @Override
    protected void doRun() {
      /*
       * Do not cache and re-use the value of socketWrapper.getSocket() in
       * this method. If the socket closes the value will be updated to
       * CLOSED_NIO_CHANNEL and the previous value potentially re-used for
       * a new connection. That can result in a stale cached value which
       * in turn can result in unintentionally closing currently active
       * connections.
       */
      // 获取客户端轮询器
      Poller poller = NioEndpoint.this.poller;
      if (poller == null) {
        socketWrapper.close();
        return;
      }
      
      try {
        int handshake = -1;
        try {
          if (socketWrapper.getSocket().isHandshakeComplete()) {
            // No TLS handshaking required. Let the handler
            // process this socket / event combination.
            handshake = 0;
          } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT
            || event == SocketEvent.ERROR) {
            // Unable to complete the TLS handshake. Treat it as
            // if the handshake failed.
            handshake = -1;
          } else {
            handshake = socketWrapper.getSocket()
              .handshake(event == SocketEvent.OPEN_READ, event == SocketEvent.OPEN_WRITE);
            // The handshake process reads/writes from/to the
            // socket. status may therefore be OPEN_WRITE once
            // the handshake completes. However, the handshake
            // happens when the socket is opened so the status
            // must always be OPEN_READ after it completes. It
            // is OK to always set this as it is only used if
            // the handshake completes.
            // 设置 socket 事件为打开读
            event = SocketEvent.OPEN_READ;
          }
        } catch (IOException x) {
          handshake = -1;
          if (log.isDebugEnabled()) {
            log.debug("Error during SSL handshake", x);
          }
        } catch (CancelledKeyException ckx) {
          handshake = -1;
        }
        
        // 握手？
        if (handshake == 0) {
          //
          SocketState state = SocketState.OPEN;
          // Process the request from this socket
          // 处理 socket 的请求，通过获取处理器来执行处理
          if (event == null) {
            state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
          } else {
            // 请求处理完毕
            state = getHandler().process(socketWrapper, event);
          }
          if (state == SocketState.CLOSED) {
            poller.cancelledKey(getSelectionKey(), socketWrapper);
          }
        } else if (handshake == -1) {
          getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
          poller.cancelledKey(getSelectionKey(), socketWrapper);
        } else if (handshake == SelectionKey.OP_READ) {
          socketWrapper.registerReadInterest();
        } else if (handshake == SelectionKey.OP_WRITE) {
          socketWrapper.registerWriteInterest();
        }
      } catch (CancelledKeyException cx) {
        poller.cancelledKey(getSelectionKey(), socketWrapper);
      } catch (VirtualMachineError vme) {
        ExceptionUtils.handleThrowable(vme);
      } catch (Throwable t) {
        log.error(sm.getString("endpoint.processing.fail"), t);
        poller.cancelledKey(getSelectionKey(), socketWrapper);
      } finally {
        socketWrapper = null;
        event = null;
        //return to cache
        if (running && !paused && processorCache != null) {
          processorCache.push(this);
        }
      }
    }
    
    private SelectionKey getSelectionKey() {
      SocketChannel socketChannel = socketWrapper.getSocket().getIOChannel();
      if (socketChannel == null) {
        return null;
      }
      
      return socketChannel.keyFor(NioEndpoint.this.poller.getSelector());
    }
    
  }
  
}
