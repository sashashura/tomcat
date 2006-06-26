/*
 *  Copyright 2005-2006 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.Executor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.jni.Error;
import org.apache.tomcat.jni.Library;
import org.apache.tomcat.jni.Poll;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.jni.Status;
import org.apache.tomcat.util.res.StringManager;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Sendfile thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 * @author Filip Hanik
 */
public class NioEndpoint {


    // -------------------------------------------------------------- Constants


    protected static Log log = LogFactory.getLog(NioEndpoint.class);

    protected static StringManager sm =
        StringManager.getManager("org.apache.tomcat.util.net.res");


    /**
     * The Request attribute key for the cipher suite.
     */
    public static final String CIPHER_SUITE_KEY = "javax.servlet.request.cipher_suite";

    /**
     * The Request attribute key for the key size.
     */
    public static final String KEY_SIZE_KEY = "javax.servlet.request.key_size";

    /**
     * The Request attribute key for the client certificate chain.
     */
    public static final String CERTIFICATE_KEY = "javax.servlet.request.X509Certificate";

    /**
     * The Request attribute key for the session id.
     * This one is a Tomcat extension to the Servlet spec.
     */
    public static final String SESSION_ID_KEY = "javax.servlet.request.ssl_session";


    // ----------------------------------------------------------------- Fields


    /**
     * Available workers.
     */
    protected WorkerStack workers = null;


    /**
     * Running state of the endpoint.
     */
    protected volatile boolean running = false;


    /**
     * Will be set to true whenever the endpoint is paused.
     */
    protected volatile boolean paused = false;


    /**
     * Track the initialization state of the endpoint.
     */
    protected boolean initialized = false;


    /**
     * Current worker threads busy count.
     */
    protected int curThreadsBusy = 0;


    /**
     * Current worker threads count.
     */
    protected int curThreads = 0;


    /**
     * Sequence number used to generate thread names.
     */
    protected int sequence = 0;


    /**
     * Root APR memory pool.
     */
    protected long rootPool = 0;


    /**
     * Server socket "pointer".
     */
    protected ServerSocketChannel serverSock = null;


    /**
     * APR memory pool for the server socket.
     */
    protected long serverSockPool = 0;


    /**
     * SSL context.
     */
    protected long sslContext = 0;


    // ------------------------------------------------------------- Properties


    /**
     * External Executor based thread pool.
     */
    protected Executor executor = null;
    public void setExecutor(Executor executor) { this.executor = executor; }
    public Executor getExecutor() { return executor; }


    /**
     * Maximum amount of worker threads.
     */
    protected int maxThreads = 40;
    public void setMaxThreads(int maxThreads) { this.maxThreads = maxThreads; }
    public int getMaxThreads() { return maxThreads; }


    /**
     * Priority of the acceptor and poller threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;
    public void setThreadPriority(int threadPriority) { this.threadPriority = threadPriority; }
    public int getThreadPriority() { return threadPriority; }


    /**
     * Size of the socket poller.
     */
    protected int pollerSize = 8 * 1024;
    public void setPollerSize(int pollerSize) { this.pollerSize = pollerSize; }
    public int getPollerSize() { return pollerSize; }


    /**
     * Size of the sendfile (= concurrent files which can be served).
     */
    protected int sendfileSize = 1 * 1024;
    public void setSendfileSize(int sendfileSize) { this.sendfileSize = sendfileSize; }
    public int getSendfileSize() { return sendfileSize; }


    /**
     * Server socket port.
     */
    protected int port;
    public int getPort() { return port; }
    public void setPort(int port ) { this.port=port; }


    /**
     * Address for the server socket.
     */
    protected InetAddress address;
    public InetAddress getAddress() { return address; }
    public void setAddress(InetAddress address) { this.address = address; }


    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }


    /**
     * Allows the server developer to specify the backlog that
     * should be used for server sockets. By default, this value
     * is 100.
     */
    protected int backlog = 100;
    public void setBacklog(int backlog) { if (backlog > 0) this.backlog = backlog; }
    public int getBacklog() { return backlog; }


    /**
     * Socket TCP no delay.
     */
    protected boolean tcpNoDelay = false;
    public boolean getTcpNoDelay() { return tcpNoDelay; }
    public void setTcpNoDelay(boolean tcpNoDelay) { this.tcpNoDelay = tcpNoDelay; }


    /**
     * Socket linger.
     */
    protected int soLinger = 100;
    public int getSoLinger() { return soLinger; }
    public void setSoLinger(int soLinger) { this.soLinger = soLinger; }


    /**
     * Socket timeout.
     */
    protected int soTimeout = -1;
    public int getSoTimeout() { return soTimeout; }
    public void setSoTimeout(int soTimeout) { this.soTimeout = soTimeout; }


    /**
     * Timeout on first request read before going to the poller, in ms.
     */
    protected int firstReadTimeout = 60000;
    public int getFirstReadTimeout() { return firstReadTimeout; }
    public void setFirstReadTimeout(int firstReadTimeout) { this.firstReadTimeout = firstReadTimeout; }


    /**
     * Poll interval, in microseconds. The smaller the value, the more CPU the poller
     * will use, but the more responsive to activity it will be.
     */
    protected int pollTime = 2000;
    public int getPollTime() { return pollTime; }
    public void setPollTime(int pollTime) { if (pollTime > 0) { this.pollTime = pollTime; } }


    /**
     * The default is true - the created threads will be
     *  in daemon mode. If set to false, the control thread
     *  will not be daemon - and will keep the process alive.
     */
    protected boolean daemon = true;
    public void setDaemon(boolean b) { daemon = b; }
    public boolean getDaemon() { return daemon; }


    /**
     * Name of the thread pool, which will be used for naming child threads.
     */
    protected String name = "TP";
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }


    /**
     * Use endfile for sending static files.
     */
    protected boolean useSendfile = Library.APR_HAS_SENDFILE;
    public void setUseSendfile(boolean useSendfile) { this.useSendfile = useSendfile; }
    public boolean getUseSendfile() { return useSendfile; }


    /**
     * Allow comet request handling.
     */
    protected boolean useComet = true;
    public void setUseComet(boolean useComet) { this.useComet = useComet; }
    public boolean getUseComet() { return useComet; }


    /**
     * Acceptor thread count.
     */
    protected int acceptorThreadCount = 0;
    public void setAcceptorThreadCount(int acceptorThreadCount) { this.acceptorThreadCount = acceptorThreadCount; }
    public int getAcceptorThreadCount() { return acceptorThreadCount; }


    /**
     * Sendfile thread count.
     */
    protected int sendfileThreadCount = 0;
    public void setSendfileThreadCount(int sendfileThreadCount) { this.sendfileThreadCount = sendfileThreadCount; }
    public int getSendfileThreadCount() { return sendfileThreadCount; }


    /**
     * Poller thread count.
     */
    protected int pollerThreadCount = 0;
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    protected long selectorTimeout = 5000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.
     */
    protected Poller[] pollers = null;
    protected int pollerRoundRobin = 0;
    public Poller getPoller() {
        pollerRoundRobin = (pollerRoundRobin + 1) % pollers.length;
        Poller poller = pollers[pollerRoundRobin];
        poller.comet = false;
        return poller;
    }


    /**
     * The socket poller used for Comet support.
     */
    public Poller getCometPoller() {
        Poller poller = getPoller();
        poller.comet = true;
        return poller;
    }


    /**
     * The static file sender.
     */
    protected Sendfile[] sendfiles = null;
    protected int sendfileRoundRobin = 0;
    public Sendfile getSendfile() {
        sendfileRoundRobin = (sendfileRoundRobin + 1) % sendfiles.length;
        return sendfiles[sendfileRoundRobin];
    }


    /**
     * Dummy maxSpareThreads property.
     */
    public int getMaxSpareThreads() { return 0; }


    /**
     * Dummy minSpareThreads property.
     */
    public int getMinSpareThreads() { return 0; }


    /**
     * SSL engine.
     */
    protected String SSLEngine = "off";
    public String getSSLEngine() { return SSLEngine; }
    public void setSSLEngine(String SSLEngine) { this.SSLEngine = SSLEngine; }


    /**
     * SSL protocols.
     */
    protected String SSLProtocol = "all";
    public String getSSLProtocol() { return SSLProtocol; }
    public void setSSLProtocol(String SSLProtocol) { this.SSLProtocol = SSLProtocol; }


    /**
     * SSL password (if a cert is encrypted, and no password has been provided, a callback
     * will ask for a password).
     */
    protected String SSLPassword = null;
    public String getSSLPassword() { return SSLPassword; }
    public void setSSLPassword(String SSLPassword) { this.SSLPassword = SSLPassword; }


    /**
     * SSL cipher suite.
     */
    protected String SSLCipherSuite = "ALL";
    public String getSSLCipherSuite() { return SSLCipherSuite; }
    public void setSSLCipherSuite(String SSLCipherSuite) { this.SSLCipherSuite = SSLCipherSuite; }


    /**
     * SSL certificate file.
     */
    protected String SSLCertificateFile = null;
    public String getSSLCertificateFile() { return SSLCertificateFile; }
    public void setSSLCertificateFile(String SSLCertificateFile) { this.SSLCertificateFile = SSLCertificateFile; }


    /**
     * SSL certificate key file.
     */
    protected String SSLCertificateKeyFile = null;
    public String getSSLCertificateKeyFile() { return SSLCertificateKeyFile; }
    public void setSSLCertificateKeyFile(String SSLCertificateKeyFile) { this.SSLCertificateKeyFile = SSLCertificateKeyFile; }


    /**
     * SSL certificate chain file.
     */
    protected String SSLCertificateChainFile = null;
    public String getSSLCertificateChainFile() { return SSLCertificateChainFile; }
    public void setSSLCertificateChainFile(String SSLCertificateChainFile) { this.SSLCertificateChainFile = SSLCertificateChainFile; }


    /**
     * SSL CA certificate path.
     */
    protected String SSLCACertificatePath = null;
    public String getSSLCACertificatePath() { return SSLCACertificatePath; }
    public void setSSLCACertificatePath(String SSLCACertificatePath) { this.SSLCACertificatePath = SSLCACertificatePath; }


    /**
     * SSL CA certificate file.
     */
    protected String SSLCACertificateFile = null;
    public String getSSLCACertificateFile() { return SSLCACertificateFile; }
    public void setSSLCACertificateFile(String SSLCACertificateFile) { this.SSLCACertificateFile = SSLCACertificateFile; }


    /**
     * SSL CA revocation path.
     */
    protected String SSLCARevocationPath = null;
    public String getSSLCARevocationPath() { return SSLCARevocationPath; }
    public void setSSLCARevocationPath(String SSLCARevocationPath) { this.SSLCARevocationPath = SSLCARevocationPath; }


    /**
     * SSL CA revocation file.
     */
    protected String SSLCARevocationFile = null;
    public String getSSLCARevocationFile() { return SSLCARevocationFile; }
    public void setSSLCARevocationFile(String SSLCARevocationFile) { this.SSLCARevocationFile = SSLCARevocationFile; }


    /**
     * SSL verify client.
     */
    protected String SSLVerifyClient = "none";
    public String getSSLVerifyClient() { return SSLVerifyClient; }
    public void setSSLVerifyClient(String SSLVerifyClient) { this.SSLVerifyClient = SSLVerifyClient; }


    /**
     * SSL verify depth.
     */
    protected int SSLVerifyDepth = 10;
    public int getSSLVerifyDepth() { return SSLVerifyDepth; }
    public void setSSLVerifyDepth(int SSLVerifyDepth) { this.SSLVerifyDepth = SSLVerifyDepth; }


    // --------------------------------------------------------- Public Methods


    /**
     * Number of keepalive sockets.
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int keepAliveCount = 0;
            for (int i = 0; i < pollers.length; i++) {
                keepAliveCount += pollers[i].getKeepAliveCount();
            }
            return keepAliveCount;
        }
    }


    /**
     * Number of sendfile sockets.
     */
    public int getSendfileCount() {
        if (sendfiles == null) {
            return 0;
        } else {
            int sendfileCount = 0;
            for (int i = 0; i < sendfiles.length; i++) {
                sendfileCount += sendfiles[i].getSendfileCount();
            }
            return sendfileCount;
        }
    }


    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
    public int getCurrentThreadCount() {
        return curThreads;
    }


    /**
     * Return the amount of threads currently busy.
     *
     * @return the amount of threads currently busy
     */
    public int getCurrentThreadsBusy() {
        return curThreadsBusy;
    }


    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }


    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is paused, false otherwise
     */
    public boolean isPaused() {
        return paused;
    }


    // ----------------------------------------------- Public Lifecycle Methods


    /**
     * Initialize the endpoint.
     */
    public void init()
        throws Exception {

        if (initialized)
            return;

        serverSock = ServerSocketChannel.open();
        InetSocketAddress addr = (address!=null?new InetSocketAddress(address,port):new InetSocketAddress(port));
        serverSock.socket().bind(addr,100); //todo, set backlog value
        serverSock.configureBlocking(true); //mimic APR behavior
        // Sendfile usage on systems which don't support it cause major problems
        if (useSendfile) {
            log.warn(sm.getString("endpoint.sendfile.nosupport"));
            useSendfile = false;
        }

        // Initialize thread count defaults for acceptor, poller and sendfile
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount != 1) {
            // limit to one poller, no need for others
            pollerThreadCount = 1;
        }
        if (sendfileThreadCount != 0) {
            sendfileThreadCount = 0;
        }

        // Initialize SSL if needed
        if (!"off".equalsIgnoreCase(SSLEngine)) {
            // Initialize SSL
            // FIXME: one per VM call ?
            if ("on".equalsIgnoreCase(SSLEngine)) {
                SSL.initialize(null);
            } else {
                SSL.initialize(SSLEngine);
            }
            // SSL protocol
            int value = SSL.SSL_PROTOCOL_ALL;
            if ("SSLv2".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_SSLV2;
            } else if ("SSLv3".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_SSLV3;
            } else if ("TLSv1".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_TLSV1;
            } else if ("SSLv2+SSLv3".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_SSLV2 | SSL.SSL_PROTOCOL_SSLV3;
            }
//            // Create SSL Context
//            sslContext = SSLContext.make(rootPool, value, SSL.SSL_MODE_SERVER);
//            // List the ciphers that the client is permitted to negotiate
//            SSLContext.setCipherSuite(sslContext, SSLCipherSuite);
//            // Load Server key and certificate
//            SSLContext.setCertificate(sslContext, SSLCertificateFile, SSLCertificateKeyFile, SSLPassword, SSL.SSL_AIDX_RSA);
//            // Set certificate chain file
//            SSLContext.setCertificateChainFile(sslContext, SSLCertificateChainFile, false);
//            // Support Client Certificates
//            SSLContext.setCACertificate(sslContext, SSLCACertificateFile, SSLCACertificatePath);
//            // Set revocation
//            SSLContext.setCARevocation(sslContext, SSLCARevocationFile, SSLCARevocationPath);
//            // Client certificate verification
//            value = SSL.SSL_CVERIFY_NONE;
//            if ("optional".equalsIgnoreCase(SSLVerifyClient)) {
//                value = SSL.SSL_CVERIFY_OPTIONAL;
//            } else if ("require".equalsIgnoreCase(SSLVerifyClient)) {
//                value = SSL.SSL_CVERIFY_REQUIRE;
//            } else if ("optionalNoCA".equalsIgnoreCase(SSLVerifyClient)) {
//                value = SSL.SSL_CVERIFY_OPTIONAL_NO_CA;
//            }
//            SSLContext.setVerify(sslContext, value, SSLVerifyDepth);
            // For now, sendfile is not supported with SSL
            useSendfile = false;
        }

        initialized = true;

    }


    /**
     * Start the APR endpoint, creating acceptor, poller and sendfile threads.
     */
    public void start()
        throws Exception {
        // Initialize socket if not done before
        if (!initialized) {
            init();
        }
        if (!running) {
            running = true;
            paused = false;

            // Create worker collection
            if (executor == null) {
                workers = new WorkerStack(maxThreads);
            }

            // Start acceptor threads
            for (int i = 0; i < acceptorThreadCount; i++) {
                Thread acceptorThread = new Thread(new Acceptor(), getName() + "-Acceptor-" + i);
                acceptorThread.setPriority(threadPriority);
                acceptorThread.setDaemon(daemon);
                acceptorThread.start();
            }

            // Start poller threads
            pollers = new Poller[pollerThreadCount];
            for (int i = 0; i < pollerThreadCount; i++) {
                pollers[i] = new Poller(false);
                pollers[i].init();
                Thread pollerThread = new Thread(pollers[i], getName() + "-Poller-" + i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }

            // Start sendfile threads
            if (useSendfile) {
                sendfiles = new Sendfile[sendfileThreadCount];
                for (int i = 0; i < sendfileThreadCount; i++) {
                    sendfiles[i] = new Sendfile();
                    sendfiles[i].init();
                    Thread sendfileThread = new Thread(sendfiles[i], getName() + "-Sendfile-" + i);
                    sendfileThread.setPriority(threadPriority);
                    sendfileThread.setDaemon(true);
                    sendfileThread.start();
                }
            }
        }
    }


    /**
     * Pause the endpoint, which will make it stop accepting new sockets.
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
        }
    }


    /**
     * Resume the endpoint, which will make it start accepting new sockets
     * again.
     */
    public void resume() {
        if (running) {
            paused = false;
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    public void stop() {
        if (running) {
            running = false;
            unlockAccept();
            for (int i = 0; i < pollers.length; i++) {
                pollers[i].destroy();
            }
            pollers = null;
            if (useSendfile) {
                for (int i = 0; i < sendfiles.length; i++) {
                    sendfiles[i].destroy();
                }
                sendfiles = null;
            }
        }
    }


    /**
     * Deallocate APR memory pools, and close server socket.
     */
    public void destroy() throws Exception {
        if (running) {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        sslContext = 0;
        initialized = false;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Get a sequence number used for thread naming.
     */
    protected int getSequence() {
        return sequence++;
    }


    /**
     * Unlock the server socket accept using a bugus connection.
     */
    protected void unlockAccept() {
        java.net.Socket s = null;
        try {
            // Need to create a connection to unlock the accept();
            if (address == null) {
                s = new java.net.Socket("127.0.0.1", port);
            } else {
                s = new java.net.Socket(address, port);
                // setting soLinger to a small value will help shutdown the
                // connection quicker
                s.setSoLinger(true, 0);
            }
        } catch(Exception e) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.unlock", "" + port), e);
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }


    /**
     * Process the specified connection.
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        int step = 1;
        try {
            //disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);

            // 1: Set socket options: timeout, linger, etc
            if (soLinger >= 0)
                socket.socket().setSoLinger(true,soLinger);
            if (tcpNoDelay)
                socket.socket().setTcpNoDelay(true);
            if (soTimeout > 0)
                socket.socket().setSoTimeout(soTimeout);


            // 2: SSL handshake
            step = 2;
            if (sslContext != 0) {
//                SSLSocket.attach(sslContext, socket);
//                if (SSLSocket.handshake(socket) != 0) {
//                    if (log.isDebugEnabled()) {
//                        log.debug(sm.getString("endpoint.err.handshake") + ": " + SSL.getLastError());
//                    }
//                    return false;
//                }
            }
            
            getPoller().register(socket);

        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                if (step == 2) {
                    log.debug(sm.getString("endpoint.err.handshake"), t);
                } else {
                    log.debug(sm.getString("endpoint.err.unexpected"), t);
                }
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    /**
     * Create (or allocate) and return an available processor for use in
     * processing a specific HTTP request, if possible.  If the maximum
     * allowed processors have already been created and are in use, return
     * <code>null</code> instead.
     */
    protected Worker createWorkerThread() {

        synchronized (workers) {
            if (workers.size() > 0) {
                curThreadsBusy++;
                return (workers.pop());
            }
            if ((maxThreads > 0) && (curThreads < maxThreads)) {
                curThreadsBusy++;
                return (newWorkerThread());
            } else {
                if (maxThreads < 0) {
                    curThreadsBusy++;
                    return (newWorkerThread());
                } else {
                    return (null);
                }
            }
        }

    }


    /**
     * Create and return a new processor suitable for processing HTTP
     * requests and returning the corresponding responses.
     */
    protected Worker newWorkerThread() {

        Worker workerThread = new Worker();
        workerThread.start();
        return (workerThread);

    }


    /**
     * Return a new worker thread, and block while to worker is available.
     */
    protected Worker getWorkerThread() {
        // Allocate a new worker thread
        Worker workerThread = createWorkerThread();
        while (workerThread == null) {
            try {
                synchronized (workers) {
                    workers.wait();
                }
            } catch (InterruptedException e) {
                // Ignore
            }
            workerThread = createWorkerThread();
        }
        return workerThread;
    }


    /**
     * Recycle the specified Processor so that it can be used again.
     *
     * @param workerThread The processor to be recycled
     */
    protected void recycleWorkerThread(Worker workerThread) {
        synchronized (workers) {
            workers.push(workerThread);
            curThreadsBusy--;
            workers.notify();
        }
    }


    /**
     * Allocate a new poller of the specified size.
     */
    protected long allocatePoller(int size, long pool, int timeout) {
        try {
            return Poll.create(size, pool, 0, timeout * 1000);
        } catch (Error e) {
            if (Status.APR_STATUS_IS_EINVAL(e.getError())) {
                log.info(sm.getString("endpoint.poll.limitedpollsize", "" + size));
                return 0;
            } else {
                log.error(sm.getString("endpoint.poll.initfail"), e);
                return -1;
            }
        }
    }


    /**
     * Process given socket.
     */
    protected boolean processSocket(SocketChannel socket) {
        try {
            if (executor == null) {
                getWorkerThread().assign(socket);
            }  else {
                executor.execute(new SocketProcessor(socket));
            }
        } catch (Throwable t) {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    /**
     * Process given socket for an event.
     */
    protected boolean processSocket(SocketChannel socket, boolean error) {
        try {
            if (executor == null) {
                getWorkerThread().assign(socket, error);
            } else {
                executor.execute(new SocketEventProcessor(socket, error));
            }
        } catch (Throwable t) {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    // --------------------------------------------------- Acceptor Inner Class


    /**
     * Server socket acceptor thread.
     */
    protected class Acceptor implements Runnable {


        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                try {
                    // Accept the next incoming connection from the server socket
                    SocketChannel socket = serverSock.accept();
                    // Hand this socket off to an appropriate processor
                    if(!setSocketOptions(socket))
                    {
                        // Close socket right away
                        socket.socket().close();
                        socket.close();
                    }
                } catch (Throwable t) {
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }

                // The processor will recycle itself when it finishes

            }

        }

    }


    // ----------------------------------------------------- Poller Inner Class


    /**
     * Poller class.
     */
    public class Poller implements Runnable {

        protected Selector selector;
        protected LinkedList<Runnable> events = new LinkedList<Runnable>();
        protected boolean close = false;
        protected boolean comet = true;

        protected int keepAliveCount = 0;
        public int getKeepAliveCount() { return keepAliveCount; }



        public Poller(boolean comet) throws IOException {
            this.comet = comet;
            this.selector = Selector.open();
        }
        
        public Selector getSelector() { return selector;}

        /**
         * Create the poller. With some versions of APR, the maximum poller size will
         * be 62 (reocmpiling APR is necessary to remove this limitation).
         */
        protected void init() {
            keepAliveCount = 0;
        }

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel descturction of sockets which are still
            // in the poller can cause problems
            try {
                synchronized (this) {
                    this.wait(pollTime / 1000);
                }
            } catch (InterruptedException e) {
                // Ignore
            }
            close = true;
        }
        
        public void addEvent(Runnable event) {
            synchronized (events) {
                events.add(event);
            }
            selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(final SocketChannel socket) {
            final SelectionKey key = socket.keyFor(selector);
            KeyAttachment att = (KeyAttachment)key.attachment();
            if ( att != null ) att.setWakeUp(false);
            Runnable r = new Runnable() {
                public void run() {
                    try {
                        if (key != null) key.interestOps(SelectionKey.OP_READ);
                    }catch ( CancelledKeyException ckx ) {
                        try {
                            socket.socket().close();
                            socket.close();
                        } catch ( Exception ignore ) {}
                    }
                }
            };
            addEvent(r);
        }

        public void events() {
            synchronized (events) {
                Runnable r = null;
                while ( (events.size() > 0) && (r = events.removeFirst()) != null ) {
                    try {
                        r.run();
                    } catch ( Exception x ) {
                        log.error("",x);
                    }
                }
                events.clear();
            }
        }
        
        public void register(final SocketChannel socket)
        {
            SelectionKey key = socket.keyFor(selector);
            Runnable r = new Runnable() {
                public void run() {
                    try {
                        socket.register(selector, SelectionKey.OP_READ, new KeyAttachment());
                    } catch (Exception x) {
                        log.error("", x);
                    }
                }
    
            };
            synchronized (events) {
                events.add(r);
            }
            selector.wakeup();
        }
        
        public void cancelledKey(SelectionKey key) {
            try {
                KeyAttachment ka = (KeyAttachment) key.attachment();
                key.cancel();
                if (ka.getComet()) processSocket( (SocketChannel) key.channel(), true);
                key.channel().close();
            } catch (IOException e) {
                if ( log.isDebugEnabled() ) log.debug("",e);
                // Ignore
            }
        }
        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Loop until we receive a shutdown command
            while (running) {
                // Loop if endpoint is paused
                while (paused) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                events();
                // Time to terminate?
                if (close) return;

                int keyCount = 0;
                try {
                    keyCount = selector.select(selectorTimeout);
                } catch (Throwable x) {
                    log.error("",x);
                    continue;
                }
                
            

                //if (keyCount == 0) continue;

                Iterator iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = (SelectionKey) iterator.next();
                    iterator.remove();
                    KeyAttachment attachment = (KeyAttachment)sk.attachment();
                    try {
                        if(attachment == null) attachment = new KeyAttachment();
                        attachment.access();
                        sk.attach(attachment);

                        int readyOps = sk.readyOps();
                        sk.interestOps(sk.interestOps() & ~readyOps);
                        SocketChannel channel = (SocketChannel)sk.channel();
                        boolean read = sk.isReadable();
                        if (read) {
                            if ( attachment.getWakeUp() ) {
                                attachment.setWakeUp(false);
                                synchronized (attachment.getMutex()) {attachment.getMutex().notifyAll();}
                            } else if ( comet ) {
                                if (!processSocket(channel,false)) processSocket(channel,true);
                            } else {
                                boolean close = (!processSocket(channel));
                                if ( close ) {
                                    channel.socket().close();
                                    channel.close();
                                }
                            }
                        }
                        if (sk.isValid() && sk.isWritable()) {
                        }
                    } catch ( CancelledKeyException ckx ) {
                        if (attachment!=null && attachment.getComet()) processSocket( (SocketChannel) sk.channel(), true);
                        try {
                            sk.channel().close();
                        }catch ( Exception ignore){}
                    } catch (Throwable t) {
                        log.error("",t);
                    }
                }//while

                //timeout
                Set keys = selector.keys();
                long now = System.currentTimeMillis();
                for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
                    SelectionKey key = (SelectionKey) iter.next();
                    try {
                        if (key.interestOps() == SelectionKey.OP_READ) {
                            //only timeout sockets that we are waiting for a read from
                            KeyAttachment ka = (KeyAttachment) key.attachment();
                            long delta = now - ka.getLastAccess();
                            if (delta > (long) soTimeout) {
                                cancelledKey(key);
                            }
                        }
                    }catch ( CancelledKeyException ckx ) {
                        cancelledKey(key);
                    }
                }

            }
            synchronized (this) {
                this.notifyAll();
            }

        }

    }
    
    public static class KeyAttachment {

        public long getLastAccess() { return lastAccess; }
        public void access() { access(System.currentTimeMillis()); }
        public void access(long access) { lastAccess = access; }
        public void setComet(boolean comet) { this.comet = comet; }
        public boolean getComet() { return comet; }
        public boolean getCurrentAccess() { return currentAccess; }
        public void setCurrentAccess(boolean access) { currentAccess = access; }
        public boolean getWakeUp() { return wakeUp; }
        public void setWakeUp(boolean wakeUp) { this.wakeUp = wakeUp; }
        public Object getMutex() {return mutex;}
        protected Object mutex = new Object();
        protected boolean wakeUp = false;
        protected long lastAccess = System.currentTimeMillis();
        protected boolean currentAccess = false;
        protected boolean comet = false;

    }



    // ----------------------------------------------------- Worker Inner Class


    /**
     * Server processor class.
     */
    protected class Worker implements Runnable {


        protected Thread thread = null;
        protected boolean available = false;
        protected SocketChannel socket = null;
        protected boolean event = false;
        protected boolean error = false;


        /**
         * Process an incoming TCP/IP connection on the specified socket.  Any
         * exception that occurs during processing must be logged and swallowed.
         * <b>NOTE</b>:  This method is called from our Connector's thread.  We
         * must assign it to our own thread so that multiple simultaneous
         * requests can be handled.
         *
         * @param socket TCP socket to process
         */
        protected synchronized void assign(SocketChannel socket) {

            // Wait for the Processor to get the previous Socket
            while (available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            event = false;
            error = false;
            available = true;
            notifyAll();

        }


        protected synchronized void assign(SocketChannel socket, boolean error) {

            // Wait for the Processor to get the previous Socket
            while (available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            event = true;
            this.error = error;
            available = true;
            notifyAll();
        }


        /**
         * Await a newly assigned Socket from our Connector, or <code>null</code>
         * if we are supposed to shut down.
         */
        protected synchronized SocketChannel await() {

            // Wait for the Connector to provide a new Socket
            while (!available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Notify the Connector that we have received this Socket
            SocketChannel socket = this.socket;
            available = false;
            notifyAll();

            return (socket);

        }


        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Process requests until we receive a shutdown signal
            while (running) {

                // Wait for the next socket to be assigned
                SocketChannel socket = await();
                if (socket == null)
                    continue;

                // Process the request from this socket
                if ((event) && (handler.event(socket, error) == Handler.SocketState.CLOSED)) {
                    // Close socket and pool
                    try {
                        socket.socket().close();
                        socket.close();
                    }catch ( Exception x ) {
                        log.error("",x);
                    }
                } else if ((!event) && (handler.process(socket) == Handler.SocketState.CLOSED)) {
                    // Close socket and pool
                    try {
                        socket.socket().close();
                        socket.close();
                    }catch ( Exception x ) {
                        log.error("",x);
                    }
                }

                // Finish up this request
                recycleWorkerThread(this);

            }

        }


        /**
         * Start the background processing thread.
         */
        public void start() {
            thread = new Thread(this);
            thread.setName(getName() + "-" + (++curThreads));
            thread.setDaemon(true);
            thread.start();
        }


    }


    // ----------------------------------------------- SendfileData Inner Class


    /**
     * SendfileData class.
     */
    public static class SendfileData {
        // File
        public String fileName;
        public long fd;
        public long fdpool;
        // Range information
        public long start;
        public long end;
        // Socket and socket pool
        public SocketChannel socket;
        // Position
        public long pos;
        // KeepAlive flag
        public boolean keepAlive;
    }


    // --------------------------------------------------- Sendfile Inner Class


    /**
     * Sendfile class.
     */
    public class Sendfile implements Runnable {

        protected long sendfilePollset = 0;
        protected long pool = 0;
        protected long[] desc;
        protected HashMap<Long, SendfileData> sendfileData;

        protected int sendfileCount;
        public int getSendfileCount() { return sendfileCount; }

        protected ArrayList<SendfileData> addS;

        /**
         * Create the sendfile poller. With some versions of APR, the maximum poller size will
         * be 62 (reocmpiling APR is necessary to remove this limitation).
         */
        protected void init() {
//            pool = Pool.create(serverSockPool);
//            int size = sendfileSize / sendfileThreadCount;
//            sendfilePollset = allocatePoller(size, pool, soTimeout);
//            if (sendfilePollset == 0 && size > 1024) {
//                size = 1024;
//                sendfilePollset = allocatePoller(size, pool, soTimeout);
//            }
//            if (sendfilePollset == 0) {
//                size = 62;
//                sendfilePollset = allocatePoller(size, pool, soTimeout);
//            }
//            desc = new long[size * 2];
//            sendfileData = new HashMap<Long, SendfileData>(size);
//            addS = new ArrayList<SendfileData>();
        }

        /**
         * Destroy the poller.
         */
        protected void destroy() {
//            // Wait for polltime before doing anything, so that the poller threads
//            // exit, otherwise parallel descturction of sockets which are still
//            // in the poller can cause problems
//            try {
//                synchronized (this) {
//                    this.wait(pollTime / 1000);
//                }
//            } catch (InterruptedException e) {
//                // Ignore
//            }
//            // Close any socket remaining in the add queue
//            for (int i = (addS.size() - 1); i >= 0; i--) {
//                SendfileData data = addS.get(i);
//                Socket.destroy(data.socket);
//            }
//            // Close all sockets still in the poller
//            int rv = Poll.pollset(sendfilePollset, desc);
//            if (rv > 0) {
//                for (int n = 0; n < rv; n++) {
//                    Socket.destroy(desc[n*2+1]);
//                }
//            }
//            Pool.destroy(pool);
//            sendfileData.clear();
        }

        /**
         * Add the sendfile data to the sendfile poller. Note that in most cases,
         * the initial non blocking calls to sendfile will return right away, and
         * will be handled asynchronously inside the kernel. As a result,
         * the poller will never be used.
         *
         * @param data containing the reference to the data which should be snet
         * @return true if all the data has been sent right away, and false
         *              otherwise
         */
        public boolean add(SendfileData data) {
//            // Initialize fd from data given
//            try {
//                data.fdpool = Socket.pool(data.socket);
//                data.fd = File.open
//                    (data.fileName, File.APR_FOPEN_READ
//                     | File.APR_FOPEN_SENDFILE_ENABLED | File.APR_FOPEN_BINARY,
//                     0, data.fdpool);
//                data.pos = data.start;
//                // Set the socket to nonblocking mode
//                Socket.timeoutSet(data.socket, 0);
//                while (true) {
//                    long nw = Socket.sendfilen(data.socket, data.fd,
//                                               data.pos, data.end - data.pos, 0);
//                    if (nw < 0) {
//                        if (!(-nw == Status.EAGAIN)) {
//                            Socket.destroy(data.socket);
//                            data.socket = 0;
//                            return false;
//                        } else {
//                            // Break the loop and add the socket to poller.
//                            break;
//                        }
//                    } else {
//                        data.pos = data.pos + nw;
//                        if (data.pos >= data.end) {
//                            // Entire file has been sent
//                            Pool.destroy(data.fdpool);
//                            // Set back socket to blocking mode
//                            Socket.timeoutSet(data.socket, soTimeout * 1000);
//                            return true;
//                        }
//                    }
//                }
//            } catch (Exception e) {
//                log.error(sm.getString("endpoint.sendfile.error"), e);
//                return false;
//            }
//            // Add socket to the list. Newly added sockets will wait
//            // at most for pollTime before being polled
//            synchronized (this) {
//                addS.add(data);
//                this.notify();
//            }
            return false;
        }

        /**
         * Remove socket from the poller.
         *
         * @param data the sendfile data which should be removed
         */
        protected void remove(SendfileData data) {
//            int rv = Poll.remove(sendfilePollset, data.socket);
//            if (rv == Status.APR_SUCCESS) {
//                sendfileCount--;
//            }
//            sendfileData.remove(data);
        }

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

//            // Loop until we receive a shutdown command
//            while (running) {
//
//                // Loop if endpoint is paused
//                while (paused) {
//                    try {
//                        Thread.sleep(1000);
//                    } catch (InterruptedException e) {
//                        // Ignore
//                    }
//                }
//
//                while (sendfileCount < 1 && addS.size() < 1) {
//                    try {
//                        synchronized (this) {
//                            this.wait();
//                        }
//                    } catch (InterruptedException e) {
//                        // Ignore
//                    }
//                }
//
//                try {
//                    // Add socket to the poller
//                    if (addS.size() > 0) {
//                        synchronized (this) {
//                            for (int i = (addS.size() - 1); i >= 0; i--) {
//                                SendfileData data = addS.get(i);
//                                int rv = Poll.add(sendfilePollset, data.socket, Poll.APR_POLLOUT);
//                                if (rv == Status.APR_SUCCESS) {
//                                    sendfileData.put(new Long(data.socket), data);
//                                    sendfileCount++;
//                                } else {
//                                    log.warn(sm.getString("endpoint.sendfile.addfail", "" + rv, Error.strerror(rv)));
//                                    // Can't do anything: close the socket right away
//                                    Socket.destroy(data.socket);
//                                }
//                            }
//                            addS.clear();
//                        }
//                    }
//                    // Pool for the specified interval
//                    int rv = Poll.poll(sendfilePollset, pollTime, desc, false);
//                    if (rv > 0) {
//                        for (int n = 0; n < rv; n++) {
//                            // Get the sendfile state
//                            SendfileData state =
//                                sendfileData.get(new Long(desc[n*2+1]));
//                            // Problem events
//                            if (((desc[n*2] & Poll.APR_POLLHUP) == Poll.APR_POLLHUP)
//                                    || ((desc[n*2] & Poll.APR_POLLERR) == Poll.APR_POLLERR)) {
//                                // Close socket and clear pool
//                                remove(state);
//                                // Destroy file descriptor pool, which should close the file
//                                // Close the socket, as the reponse would be incomplete
//                                Socket.destroy(state.socket);
//                                continue;
//                            }
//                            // Write some data using sendfile
//                            long nw = Socket.sendfilen(state.socket, state.fd,
//                                                       state.pos,
//                                                       state.end - state.pos, 0);
//                            if (nw < 0) {
//                                // Close socket and clear pool
//                                remove(state);
//                                // Close the socket, as the reponse would be incomplete
//                                // This will close the file too.
//                                Socket.destroy(state.socket);
//                                continue;
//                            }
//
//                            state.pos = state.pos + nw;
//                            if (state.pos >= state.end) {
//                                remove(state);
//                                if (state.keepAlive) {
//                                    // Destroy file descriptor pool, which should close the file
//                                    Pool.destroy(state.fdpool);
//                                    Socket.timeoutSet(state.socket, soTimeout * 1000);
//                                    // If all done hand this socket off to a worker for
//                                    // processing of further requests
//                                    if (!processSocket(state.socket)) {
//                                        Socket.destroy(state.socket);
//                                    }
//                                } else {
//                                    // Close the socket since this is
//                                    // the end of not keep-alive request.
//                                    Socket.destroy(state.socket);
//                                }
//                            }
//                        }
//                    } else if (rv < 0) {
//                        int errn = -rv;
//                        /* Any non timeup or interrupted error is critical */
//                        if ((errn != Status.TIMEUP) && (errn != Status.EINTR)) {
//                            if (errn >  Status.APR_OS_START_USERERR) {
//                                errn -=  Status.APR_OS_START_USERERR;
//                            }
//                            log.error(sm.getString("endpoint.poll.fail", "" + errn, Error.strerror(errn)));
//                            // Handle poll critical failure
//                            synchronized (this) {
//                                destroy();
//                                init();
//                            }
//                            continue;
//                        }
//                    }
//                    /* TODO: See if we need to call the maintain for sendfile poller */
//                } catch (Throwable t) {
//                    log.error(sm.getString("endpoint.poll.error"), t);
//                }
//            }
//
//            synchronized (this) {
//                this.notifyAll();
//            }

        }

    }


    // ------------------------------------------------ Handler Inner Interface


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler {
        public enum SocketState {
            OPEN, CLOSED, LONG
        }
        public SocketState process(SocketChannel socket);
        public SocketState event(SocketChannel socket, boolean error);
    }


    // ------------------------------------------------- WorkerStack Inner Class


    public class WorkerStack {

        protected Worker[] workers = null;
        protected int end = 0;

        public WorkerStack(int size) {
            workers = new Worker[size];
        }

        /** 
         * Put the object into the queue.
         * 
         * @param   object      the object to be appended to the queue (first element). 
         */
        public void push(Worker worker) {
            workers[end++] = worker;
        }

        /**
         * Get the first object out of the queue. Return null if the queue
         * is empty. 
         */
        public Worker pop() {
            if (end > 0) {
                return workers[--end];
            }
            return null;
        }

        /**
         * Get the first object out of the queue, Return null if the queue
         * is empty.
         */
        public Worker peek() {
            return workers[end];
        }

        /**
         * Is the queue empty?
         */
        public boolean isEmpty() {
            return (end == 0);
        }

        /**
         * How many elements are there in this queue?
         */
        public int size() {
            return (end);
        }
    }


    // ---------------------------------------------- SocketProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        protected SocketChannel socket = null;

        public SocketProcessor(SocketChannel socket) {
            this.socket = socket;
        }

        public void run() {

            // Process the request from this socket
            if (handler.process(socket) == Handler.SocketState.CLOSED) {
                // Close socket and pool
                try {
                    socket.socket().close();
                    socket.close();
                } catch ( Exception x ) {
                    log.error("",x);
                }
                socket = null;
            }

        }

    }


    // --------------------------------------- SocketEventProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketEventProcessor implements Runnable {

        protected SocketChannel socket = null;
        protected boolean error = false; 

        public SocketEventProcessor(SocketChannel socket, boolean error) {
            this.socket = socket;
            this.error = error;
        }

        public void run() {

            // Process the request from this socket
            if (handler.event(socket, error) == Handler.SocketState.CLOSED) {
                // Close socket and pool
                try {
                    socket.socket().close();
                    socket.close();
                } catch ( Exception x ) {
                    log.error("",x);
                }
                socket = null;
            }

        }

    }


}
