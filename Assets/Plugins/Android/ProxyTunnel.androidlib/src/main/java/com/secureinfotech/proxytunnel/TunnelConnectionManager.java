package com.secureinfotech.proxytunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class TunnelConnectionManager {
    public interface Listener {
        void onStatusChanged(String status);

        void onBytesChanged(long uploadedBytes, long downloadedBytes);

        void onError(String message);

        void onTerminalDisconnect(String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int AUTH_TIMEOUT_MS = 10_000;
    private static final int HEARTBEAT_TIMEOUT_MS = 20_000;
    private static final int HEARTBEAT_INTERVAL_MS = 15_000;
    private static final int MAX_LINE_LENGTH = 256;
    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final Context context;
    private final TunnelConfig config;
    private final String token;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object waitLock = new Object();

    private ExecutorService executor;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Socket currentSocket;
    private volatile boolean stableConnection;
    private volatile long uploadedBytes;
    private volatile long downloadedBytes;

    public TunnelConnectionManager(Context context, TunnelConfig config, String token, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.token = token;
        this.listener = listener;
    }

    public void start() {
        if (executor != null) {
            return;
        }
        registerNetworkCallback();
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ProxyTunnelConnection");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runConnectionLoop();
            }
        });
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        closeCurrentSocket();
        unregisterNetworkCallback();
        synchronized (waitLock) {
            waitLock.notifyAll();
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void shutdownAfterTerminalFailure() {
        stopped.set(true);
        closeCurrentSocket();
        unregisterNetworkCallback();
        synchronized (waitLock) {
            waitLock.notifyAll();
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private void runConnectionLoop() {
        long backoffMs = INITIAL_BACKOFF_MS;
        listener.onStatusChanged(TunnelStatus.CONNECTING);

        while (!stopped.get()) {
            if (!isNetworkAvailable()) {
                listener.onStatusChanged(TunnelStatus.RECONNECTING);
                listener.onError("Network is unavailable.");
                waitForNetworkOrDelay(INITIAL_BACKOFF_MS);
                continue;
            }

            try {
                stableConnection = false;
                connectAuthenticateAndHeartbeat();
            } catch (AuthenticationException authException) {
                closeCurrentSocket();
                listener.onTerminalDisconnect("Authentication failed.");
                return;
            } catch (IOException ioException) {
                closeCurrentSocket();
                if (!stopped.get()) {
                    listener.onStatusChanged(TunnelStatus.RECONNECTING);
                    listener.onError(safeNetworkError(ioException));
                    if (stableConnection) {
                        backoffMs = INITIAL_BACKOFF_MS;
                    }
                    waitForNetworkOrDelay(backoffMs);
                    backoffMs = Math.min(backoffMs * 2L, MAX_BACKOFF_MS);
                }
            } catch (RuntimeException runtimeException) {
                closeCurrentSocket();
                if (!stopped.get()) {
                    listener.onStatusChanged(TunnelStatus.RECONNECTING);
                    listener.onError("Tunnel worker error.");
                    if (stableConnection) {
                        backoffMs = INITIAL_BACKOFF_MS;
                    }
                    waitForNetworkOrDelay(backoffMs);
                    backoffMs = Math.min(backoffMs * 2L, MAX_BACKOFF_MS);
                }
            }
        }
    }

    private void connectAuthenticateAndHeartbeat() throws IOException, AuthenticationException {
        listener.onStatusChanged(TunnelStatus.CONNECTING);
        Socket socket = createSocket();
        currentSocket = socket;

        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        socket.setSoTimeout(AUTH_TIMEOUT_MS);
        writeLine(outputStream, "AUTH " + token);
        String authReply = readLineLimited(inputStream, MAX_LINE_LENGTH);
        if (!"OK".equals(authReply)) {
            throw new AuthenticationException();
        }

        listener.onStatusChanged(TunnelStatus.CONNECTED);
        listener.onError("");

        socket.setSoTimeout(HEARTBEAT_TIMEOUT_MS);
        while (!stopped.get()) {
            if (!isNetworkAvailable()) {
                throw new IOException("Network is unavailable.");
            }
            sleepInterruptibly(HEARTBEAT_INTERVAL_MS);
            if (stopped.get()) {
                break;
            }
            writeLine(outputStream, "PING");
            String pong = readLineLimited(inputStream, MAX_LINE_LENGTH);
            if (!"PONG".equals(pong)) {
                throw new IOException("Unexpected heartbeat reply.");
            }
            stableConnection = true;
            listener.onStatusChanged(TunnelStatus.CONNECTED);
        }
    }

    private Socket createSocket() throws IOException {
        if (!config.useTls) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS);
            return socket;
        }

        Socket plainSocket = new Socket();
        SSLSocket tlsSocket = null;
        try {
            plainSocket.connect(new InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tlsSocket = (SSLSocket) factory.createSocket(
                    plainSocket,
                    config.host,
                    config.port,
                    true);
            configureTlsSocket(tlsSocket);
            tlsSocket.startHandshake();
            verifyHostname(tlsSocket);
            return tlsSocket;
        } catch (IOException exception) {
            try {
                if (tlsSocket != null) {
                    tlsSocket.close();
                } else {
                    plainSocket.close();
                }
            } catch (IOException ignored) {
                // Closing after a failed TLS setup.
            }
            throw exception;
        }
    }

    private void configureTlsSocket(SSLSocket socket) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (shouldSendSni(config.host)) {
            setServerNameWithReflection(parameters, config.host);
        }
        socket.setSSLParameters(parameters);
    }

    private void verifyHostname(SSLSocket socket) throws IOException {
        SSLSession session = socket.getSession();
        if (session == null || !HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(config.host, session)) {
            throw new IOException("TLS hostname verification failed.");
        }
    }

    private static boolean shouldSendSni(String host) {
        if (host == null || host.length() == 0 || host.indexOf(':') >= 0) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);
            if ((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private static void setServerNameWithReflection(SSLParameters parameters, String host) {
        try {
            Class<?> sniHostNameClass = Class.forName("javax.net.ssl.SNIHostName");
            Constructor<?> constructor = sniHostNameClass.getConstructor(String.class);
            Object sniHostName = constructor.newInstance(host);
            Method setServerNames = SSLParameters.class.getMethod("setServerNames", java.util.List.class);
            setServerNames.invoke(parameters, Collections.singletonList(sniHostName));
        } catch (Exception ignored) {
            // API 23 still uses the host-associated wrapped socket plus explicit verifier below.
        }
    }

    private void writeLine(OutputStream outputStream, String line) throws IOException {
        byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        outputStream.write(data);
        outputStream.flush();
        uploadedBytes += data.length;
        listener.onBytesChanged(uploadedBytes, downloadedBytes);
    }

    private String readLineLimited(InputStream inputStream, int maxLength)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = inputStream.read();
            if (value == -1) {
                throw new IOException("Connection closed.");
            }
            downloadedBytes++;
            if (value == '\n') {
                listener.onBytesChanged(uploadedBytes, downloadedBytes);
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            if (buffer.size() >= maxLength) {
                throw new IOException("Protocol line is too long.");
            }
            buffer.write(value);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network activeNetwork = manager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(activeNetwork);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
            }

            @Override
            public void onLost(Network network) {
                listener.onStatusChanged(TunnelStatus.RECONNECTING);
                closeCurrentSocket();
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
        } catch (RuntimeException exception) {
            listener.onError("Unable to monitor network changes.");
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Callback may already be unregistered during service teardown.
        } finally {
            networkCallback = null;
        }
    }

    private void waitForNetworkOrDelay(long delayMs) {
        synchronized (waitLock) {
            if (stopped.get()) {
                return;
            }
            try {
                waitLock.wait(delayMs);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sleepInterruptibly(long delayMs) throws IOException {
        long remainingMs = delayMs;
        long deadlineMs = System.currentTimeMillis() + delayMs;
        while (!stopped.get() && remainingMs > 0L) {
            try {
                Thread.sleep(Math.min(remainingMs, 1_000L));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Tunnel worker interrupted.");
            }
            remainingMs = deadlineMs - System.currentTimeMillis();
        }
    }

    private void closeCurrentSocket() {
        Socket socket = currentSocket;
        currentSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket is being closed as part of reconnect/stop cleanup.
            }
        }
    }

    private static String safeNetworkError(IOException exception) {
        if (exception instanceof SocketTimeoutException) {
            return "Gateway timed out.";
        }
        String message = exception.getMessage();
        if (message == null || message.length() == 0) {
            return "Gateway connection failed.";
        }
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("auth")) {
            return "Gateway connection failed.";
        }
        return message;
    }

    private static final class AuthenticationException extends Exception {
    }
}
