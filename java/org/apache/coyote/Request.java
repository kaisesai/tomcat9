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
package org.apache.coyote;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ReadListener;

import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.res.StringManager;

/**
 * This is a low-level, efficient representation of a server request. Most
 * fields are GC-free, expensive operations are delayed until the  user code
 * needs the information.
 * <p>
 * Processing is delegated to modules, using a hook mechanism.
 * <p>
 * This class is not intended for user code - it is used internally by tomcat
 * for processing the request in the most efficient way. Users ( servlets ) can
 * access the information using a facade, which provides the high-level view
 * of the request.
 * <p>
 * Tomcat defines a number of attributes:
 * <ul>
 *   <li>"org.apache.tomcat.request" - allows access to the low-level
 *       request object in trusted applications
 * </ul>
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author Harish Prabandham
 * @author Alex Cruikshank [alex@epitonic.com]
 * @author Hans Bergsten [hans@gefionsoftware.com]
 * @author Costin Manolache
 * @author Remy Maucherat
 */
public final class Request {
    
    private static final StringManager sm = StringManager.getManager(Request.class);
    
    // Expected maximum typical number of cookies per request.
    private static final int INITIAL_COOKIE_SIZE = 4;
    
    // ----------------------------------------------------------- Constructors
    
    private final MessageBytes serverNameMB = MessageBytes.newInstance();
    
    // ----------------------------------------------------- Instance Variables
    
    private final MessageBytes schemeMB = MessageBytes.newInstance();
    
    private final MessageBytes methodMB = MessageBytes.newInstance();
    
    private final MessageBytes uriMB = MessageBytes.newInstance();
    
    private final MessageBytes decodedUriMB = MessageBytes.newInstance();
    
    private final MessageBytes queryMB = MessageBytes.newInstance();
    
    private final MessageBytes protoMB = MessageBytes.newInstance();
    
    // remote address/host
    private final MessageBytes remoteAddrMB = MessageBytes.newInstance();
    
    private final MessageBytes localNameMB = MessageBytes.newInstance();
    
    private final MessageBytes remoteHostMB = MessageBytes.newInstance();
    
    private final MessageBytes localAddrMB = MessageBytes.newInstance();
    
    private final MimeHeaders headers = new MimeHeaders();
    
    private final Map<String, String> trailerFields = new HashMap<>();
    
    /**
     * Path parameters
     */
    private final Map<String, String> pathParameters = new HashMap<>();
    
    /**
     * Notes.
     */
    private final Object notes[] = new Object[Constants.MAX_NOTES];
    
    /**
     * URL decoder.
     */
    private final UDecoder urlDecoder = new UDecoder();
    
    private final ServerCookies serverCookies = new ServerCookies(INITIAL_COOKIE_SIZE);
    
    private final Parameters parameters = new Parameters();
    
    private final MessageBytes remoteUser = MessageBytes.newInstance();
    
    private final MessageBytes authType = MessageBytes.newInstance();
    
    private final HashMap<String, Object> attributes = new HashMap<>();
    
    private final RequestInfo reqProcessorMX = new RequestInfo(this);
    
    private final AtomicBoolean allDataReadEventSent = new AtomicBoolean(false);
    
    volatile ReadListener listener;
    
    private int serverPort = -1;
    
    private int remotePort;
    
    private int localPort;
    
    /**
     * Associated input buffer.
     */
    private InputBuffer inputBuffer = null;
    
    /**
     * HTTP specific fields. (remove them ?)
     */
    private long contentLength = -1;
    
    private MessageBytes contentTypeMB = null;
    
    private Charset charset = null;
    
    // Retain the original, user specified character encoding so it can be
    // returned even if it is invalid
    private String characterEncoding = null;
    
    /**
     * Is there an expectation ?
     */
    private boolean expectation = false;
    
    private boolean remoteUserNeedsAuthorization = false;
    
    private Response response;
    
    private volatile ActionHook hook;
    
    private long bytesRead = 0;
    
    // Time of the request - useful to avoid repeated calls to System.currentTime
    private long startTime = -1;
    
    private int available = 0;
    
    private boolean sendfile = true;
    
    public Request() {
        parameters.setQuery(queryMB);
        parameters.setURLDecoder(urlDecoder);
    }
    
    /**
     * Parse the character encoding from the specified content type header.
     * If the content type is null, or there is no explicit character encoding,
     * <code>null</code> is returned.
     *
     * @param contentType a content type header
     */
    private static String getCharsetFromContentType(String contentType) {
        
        if (contentType == null) {
            return null;
        }
        int start = contentType.indexOf("charset=");
        if (start < 0) {
            return null;
        }
        String encoding = contentType.substring(start + 8);
        int end = encoding.indexOf(';');
        if (end >= 0) {
            encoding = encoding.substring(0, end);
        }
        encoding = encoding.trim();
        if ((encoding.length() > 2) && (encoding.startsWith("\"")) && (encoding.endsWith("\""))) {
            encoding = encoding.substring(1, encoding.length() - 1);
        }
        
        return encoding.trim();
    }
    
    public ReadListener getReadListener() {
        return listener;
    }
    
    public void setReadListener(ReadListener listener) {
        if (listener == null) {
            throw new NullPointerException(sm.getString("request.nullReadListener"));
        }
        if (getReadListener() != null) {
            throw new IllegalStateException(sm.getString("request.readListenerSet"));
        }
        // Note: This class is not used for HTTP upgrade so only need to test
        //       for async
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.ASYNC_IS_ASYNC, result);
        if (!result.get()) {
            throw new IllegalStateException(sm.getString("request.notAsync"));
        }
        
        this.listener = listener;
    }
    
    // ------------------------------------------------------------- Properties
    
    public boolean sendAllDataReadEvent() {
        return allDataReadEventSent.compareAndSet(false, true);
    }
    
    public MimeHeaders getMimeHeaders() {
        return headers;
    }
    
    public boolean isTrailerFieldsReady() {
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.IS_TRAILER_FIELDS_READY, result);
        return result.get();
    }
    
    public Map<String, String> getTrailerFields() {
        return trailerFields;
    }
    
    // -------------------- Request data --------------------
    
    public UDecoder getURLDecoder() {
        return urlDecoder;
    }
    
    public MessageBytes scheme() {
        return schemeMB;
    }
    
    public MessageBytes method() {
        return methodMB;
    }
    
    public MessageBytes requestURI() {
        return uriMB;
    }
    
    public MessageBytes decodedURI() {
        return decodedUriMB;
    }
    
    public MessageBytes queryString() {
        return queryMB;
    }
    
    public MessageBytes protocol() {
        return protoMB;
    }
    
    /**
     * Get the "virtual host", derived from the Host: header associated with
     * this request.
     *
     * @return The buffer holding the server name, if any. Use isNull() to check
     * if there is no value set.
     */
    public MessageBytes serverName() {
        return serverNameMB;
    }
    
    public int getServerPort() {
        return serverPort;
    }
    
    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }
    
    public MessageBytes remoteAddr() {
        return remoteAddrMB;
    }
    
    public MessageBytes remoteHost() {
        return remoteHostMB;
    }
    
    public MessageBytes localName() {
        return localNameMB;
    }
    
    public MessageBytes localAddr() {
        return localAddrMB;
    }
    
    public int getRemotePort() {
        return remotePort;
    }
    
    public void setRemotePort(int port) {
        this.remotePort = port;
    }
    
    public int getLocalPort() {
        return localPort;
    }
    
    // -------------------- encoding/type --------------------
    
    public void setLocalPort(int port) {
        this.localPort = port;
    }
    
    /**
     * Get the character encoding used for this request.
     *
     * @return The value set via {@link #setCharset(Charset)} or if no
     * call has been made to that method try to obtain if from the
     * content type.
     */
    public String getCharacterEncoding() {
        if (characterEncoding == null) {
            characterEncoding = getCharsetFromContentType(getContentType());
        }
        
        return characterEncoding;
    }
    
    /**
     * Get the character encoding used for this request.
     *
     * @return The value set via {@link #setCharset(Charset)} or if no
     * call has been made to that method try to obtain if from the
     * content type.
     * @throws UnsupportedEncodingException If the user agent has specified an
     *                                      invalid character encoding
     */
    public Charset getCharset() throws UnsupportedEncodingException {
        if (charset == null) {
            getCharacterEncoding();
            if (characterEncoding != null) {
                charset = B2CConverter.getCharset(characterEncoding);
            }
        }
        
        return charset;
    }
    
    public void setCharset(Charset charset) {
        this.charset = charset;
        this.characterEncoding = charset.name();
    }
    
    public int getContentLength() {
        long length = getContentLengthLong();
        
        if (length < Integer.MAX_VALUE) {
            return (int) length;
        }
        return -1;
    }
    
    public void setContentLength(long len) {
        this.contentLength = len;
    }
    
    public long getContentLengthLong() {
        if (contentLength > -1) {
            return contentLength;
        }
        
        MessageBytes clB = headers.getUniqueValue("content-length");
        contentLength = (clB == null || clB.isNull()) ? -1 : clB.getLong();
        
        return contentLength;
    }
    
    public String getContentType() {
        contentType();
        if ((contentTypeMB == null) || contentTypeMB.isNull()) {
            return null;
        }
        return contentTypeMB.toString();
    }
    
    public void setContentType(String type) {
        contentTypeMB.setString(type);
    }
    
    public void setContentType(MessageBytes mb) {
        contentTypeMB = mb;
    }
    
    public MessageBytes contentType() {
        if (contentTypeMB == null) {
            contentTypeMB = headers.getValue("content-type");
        }
        return contentTypeMB;
    }
    
    public String getHeader(String name) {
        return headers.getHeader(name);
    }
    
    public void setExpectation(boolean expectation) {
        this.expectation = expectation;
    }
    
    // -------------------- Associated response --------------------
    
    public boolean hasExpectation() {
        return expectation;
    }
    
    public Response getResponse() {
        return response;
    }
    
    public void setResponse(Response response) {
        this.response = response;
        response.setRequest(this);
    }
    
    protected void setHook(ActionHook hook) {
        this.hook = hook;
    }
    
    // -------------------- Cookies --------------------
    
    public void action(ActionCode actionCode, Object param) {
        if (hook != null) {
            if (param == null) {
                hook.action(actionCode, this);
            } else {
                hook.action(actionCode, param);
            }
        }
    }
    
    // -------------------- Parameters --------------------
    
    public ServerCookies getCookies() {
        return serverCookies;
    }
    
    public Parameters getParameters() {
        return parameters;
    }
    
    public void addPathParameter(String name, String value) {
        pathParameters.put(name, value);
    }
    
    // -------------------- Other attributes --------------------
    // We can use notes for most - need to discuss what is of general interest
    
    public String getPathParameter(String name) {
        return pathParameters.get(name);
    }
    
    public void setAttribute(String name, Object o) {
        attributes.put(name, o);
    }
    
    public HashMap<String, Object> getAttributes() {
        return attributes;
    }
    
    public Object getAttribute(String name) {
        return attributes.get(name);
    }
    
    public MessageBytes getRemoteUser() {
        return remoteUser;
    }
    
    public boolean getRemoteUserNeedsAuthorization() {
        return remoteUserNeedsAuthorization;
    }
    
    public void setRemoteUserNeedsAuthorization(boolean remoteUserNeedsAuthorization) {
        this.remoteUserNeedsAuthorization = remoteUserNeedsAuthorization;
    }
    
    public MessageBytes getAuthType() {
        return authType;
    }
    
    public int getAvailable() {
        return available;
    }
    
    public void setAvailable(int available) {
        this.available = available;
    }
    
    public boolean getSendfile() {
        return sendfile;
    }
    
    public void setSendfile(boolean sendfile) {
        this.sendfile = sendfile;
    }
    
    public boolean isFinished() {
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.REQUEST_BODY_FULLY_READ, result);
        return result.get();
    }
    
    // -------------------- Input Buffer --------------------
    
    public boolean getSupportsRelativeRedirects() {
        if (protocol().equals("") || protocol().equals("HTTP/1.0")) {
            return false;
        }
        return true;
    }
    
    public InputBuffer getInputBuffer() {
        return inputBuffer;
    }
    
    public void setInputBuffer(InputBuffer inputBuffer) {
        this.inputBuffer = inputBuffer;
    }
    
    // -------------------- debug --------------------
    
    /**
     * Read data from the input buffer and put it into ApplicationBufferHandler.
     * <p>
     * The buffer is owned by the protocol implementation - it will be reused on
     * the next read. The Adapter must either process the data in place or copy
     * it to a separate buffer if it needs to hold it. In most cases this is
     * done during byte-&gt;char conversions or via InputStream. Unlike
     * InputStream, this interface allows the app to process data in place,
     * without copy.
     *
     * @param handler The destination to which to copy the data
     * @return The number of bytes copied
     * @throws IOException If an I/O error occurs during the copy
     */
    public int doRead(ApplicationBufferHandler handler) throws IOException {
        if (getBytesRead() == 0 && !response.isCommitted()) {
            action(ActionCode.ACK, ContinueResponseTiming.ON_REQUEST_BODY_READ);
        }
        
        int n = inputBuffer.doRead(handler);
        if (n > 0) {
            bytesRead += n;
        }
        return n;
    }
    
    @Override
    public String toString() {
        return "R( " + requestURI().toString() + ")";
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    // -------------------- Per-Request "notes" --------------------
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    /**
     * Used to store private data. Thread data could be used instead - but
     * if you have the req, getting/setting a note is just an array access, may
     * be faster than ThreadLocal for very frequent operations.
     * <p>
     * Example use:
     * Catalina CoyoteAdapter:
     * ADAPTER_NOTES = 1 - stores the HttpServletRequest object ( req/res)
     * <p>
     * To avoid conflicts, note in the range 0 - 8 are reserved for the
     * servlet container ( catalina connector, etc ), and values in 9 - 16
     * for connector use.
     * <p>
     * 17-31 range is not allocated or used.
     *
     * @param pos   Index to use to store the note
     * @param value The value to store at that index
     */
    public final void setNote(int pos, Object value) {
        notes[pos] = value;
    }
    
    // -------------------- Recycling --------------------
    
    public final Object getNote(int pos) {
        return notes[pos];
    }
    
    public void recycle() {
        bytesRead = 0;
        
        contentLength = -1;
        contentTypeMB = null;
        charset = null;
        characterEncoding = null;
        expectation = false;
        headers.recycle();
        trailerFields.clear();
        serverNameMB.recycle();
        serverPort = -1;
        localAddrMB.recycle();
        localNameMB.recycle();
        localPort = -1;
        remoteAddrMB.recycle();
        remoteHostMB.recycle();
        remotePort = -1;
        available = 0;
        sendfile = true;
        
        serverCookies.recycle();
        parameters.recycle();
        pathParameters.clear();
        
        uriMB.recycle();
        decodedUriMB.recycle();
        queryMB.recycle();
        methodMB.recycle();
        protoMB.recycle();
        
        schemeMB.recycle();
        
        remoteUser.recycle();
        remoteUserNeedsAuthorization = false;
        authType.recycle();
        attributes.clear();
        
        listener = null;
        allDataReadEventSent.set(false);
        
        startTime = -1;
    }
    
    // -------------------- Info  --------------------
    public void updateCounters() {
        reqProcessorMX.updateCounters();
    }
    
    public RequestInfo getRequestProcessor() {
        return reqProcessorMX;
    }
    
    public long getBytesRead() {
        return bytesRead;
    }
    
    public boolean isProcessing() {
        return reqProcessorMX.getStage() == org.apache.coyote.Constants.STAGE_SERVICE;
    }
    
}