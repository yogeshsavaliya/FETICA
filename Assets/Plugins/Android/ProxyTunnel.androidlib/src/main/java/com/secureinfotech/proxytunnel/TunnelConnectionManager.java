package com.secureinfotech.proxytunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
    private static final int TUNNEL_READ_TIMEOUT_MS = 45_000;
    private static final int STREAM_CONNECT_TIMEOUT_MS = 10_000;
    private static final int MAX_LINE_LENGTH = 256;
    private static final int MAX_FRAME_LENGTH = 64 * 1024;
    private static final int STREAM_BUFFER_SIZE = 16 * 1024;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 15;
    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private static final byte FRAME_PING = 1;
    private static final byte FRAME_PONG = 2;
    private static final byte FRAME_OPEN = 3;
    private static final byte FRAME_OPEN_OK = 4;
    private static final byte FRAME_OPEN_ERROR = 5;
    private static final byte FRAME_DATA = 6;
    private static final byte FRAME_CLOSE = 7;

    private final Context context;
    private final TunnelConfig config;
    private final String username;
    private final String password;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object waitLock = new Object();
    private final Object tunnelWriteLock = new Object();
    private final Map<Integer, StreamState> streams = new ConcurrentHashMap<Integer, StreamState>();

    private ExecutorService connectionExecutor;
    private ExecutorService streamExecutor;
    private ScheduledExecutorService heartbeatExecutor;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Socket currentSocket;
    private volatile DataOutputStream tunnelOutput;
    private volatile boolean stableConnection;
    private volatile long uploadedBytes;
    private volatile long downloadedBytes;

    public TunnelConnectionManager(Context context, TunnelConfig config, String username, String password, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    public void start() {
        if (connectionExecutor != null) {
            return;
        }
        registerNetworkCallback();
        streamExecutor = Executors.newCachedThreadPool(new NamedThreadFactory("ProxyTunnelStream"));
        connectionExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("ProxyTunnelConnection"));
        connectionExecutor.execute(new Runnable() {
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
        closeAllStreams();
        unregisterNetworkCallback();
        synchronized (waitLock) {
            waitLock.notifyAll();
        }
        stopHeartbeat();
        if (connectionExecutor != null) {
            connectionExecutor.shutdownNow();
            connectionExecutor = null;
        }
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
            streamExecutor = null;
        }
    }

    public void shutdownAfterTerminalFailure() {
        stopped.set(true);
        closeCurrentSocket();
        closeAllStreams();
        unregisterNetworkCallback();
        synchronized (waitLock) {
            waitLock.notifyAll();
        }
        stopHeartbeat();
        if (connectionExecutor != null) {
            connectionExecutor.shutdown();
            connectionExecutor = null;
        }
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
            streamExecutor = null;
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
                connectAuthenticateAndServeReverseSocks();
            } catch (AuthenticationException authException) {
                closeCurrentSocket();
                closeAllStreams();
                listener.onTerminalDisconnect("Authentication failed.");
                return;
            } catch (IOException ioException) {
                closeCurrentSocket();
                closeAllStreams();
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
                closeAllStreams();
                if (!stopped.get()) {
                    listener.onStatusChanged(TunnelStatus.RECONNECTING);
                    listener.onError("Tunnel worker error.");
                    if (stableConnection) {
                        backoffMs = INITIAL_BACKOFF_MS;
                    }
                    waitForNetworkOrDelay(backoffMs);
                    backoffMs = Math.min(backoffMs * 2L, MAX_BACKOFF_MS);
                }
            } finally {
                stopHeartbeat();
                tunnelOutput = null;
            }
        }
    }

    private void connectAuthenticateAndServeReverseSocks() throws IOException, AuthenticationException {
        listener.onStatusChanged(TunnelStatus.CONNECTING);
        Socket socket = createSocket();
        currentSocket = socket;

        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        socket.setSoTimeout(AUTH_TIMEOUT_MS);
        writeAuthLine(outputStream, buildAuthLine());
        String authReply = readLineLimited(inputStream, MAX_LINE_LENGTH);
        if (!"OK".equals(authReply)) {
            throw new AuthenticationException();
        }

        socket.setSoTimeout(TUNNEL_READ_TIMEOUT_MS);
        DataInputStream frameInput = new DataInputStream(inputStream);
        tunnelOutput = new DataOutputStream(outputStream);
        listener.onStatusChanged(TunnelStatus.CONNECTED);
        listener.onError("");
        stableConnection = true;
        startHeartbeat();

        while (!stopped.get()) {
            if (!isNetworkAvailable()) {
                throw new IOException("Network is unavailable.");
            }
            Frame frame = readFrame(frameInput);
            handleFrame(frame);
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
            tlsSocket = (SSLSocket) factory.createSocket(plainSocket, config.host, config.port, true);
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
        if (session == null || !HttpsURLConnection.getDefaultHostnameVerifier().verify(config.host, session)) {
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

    private String buildAuthLine() {
        String encodedUsername = Base64.encodeToString(username.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String encodedPassword = Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "AUTH2 " + encodedUsername + " " + encodedPassword;
    }

    private void handleFrame(Frame frame) throws IOException {
        if (frame.type == FRAME_PONG) {
            stableConnection = true;
            return;
        }
        if (frame.type == FRAME_PING) {
            sendFrame(FRAME_PONG, 0, new byte[0]);
            return;
        }
        if (frame.type == FRAME_OPEN) {
            handleOpen(frame.streamId, frame.payload);
            return;
        }
        if (frame.type == FRAME_DATA) {
            StreamState stream = streams.get(frame.streamId);
            if (stream != null) {
                stream.write(frame.payload);
            }
            return;
        }
        if (frame.type == FRAME_CLOSE) {
            closeStream(frame.streamId, false);
            return;
        }
        throw new IOException("Unsupported tunnel frame.");
    }

    private void handleOpen(final int streamId, byte[] payload) {
        final Target target;
        try {
            target = Target.parse(payload);
        } catch (IOException exception) {
            sendOpenError(streamId, "Invalid target.");
            return;
        }

        streamExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Socket destination = new Socket();
                StreamState stream = null;
                try {
                    destination.connect(new InetSocketAddress(target.host, target.port), STREAM_CONNECT_TIMEOUT_MS);
                    destination.setTcpNoDelay(true);
                    stream = new StreamState(streamId, destination);
                    streams.put(streamId, stream);
                    sendFrame(FRAME_OPEN_OK, streamId, new byte[0]);
                    pumpDestinationToTunnel(stream);
                } catch (IOException exception) {
                    closeSocket(destination);
                    if (stream != null) {
                        streams.remove(streamId);
                    }
                    sendOpenError(streamId, "Destination connect failed.");
                }
            }
        });
    }

    private void pumpDestinationToTunnel(StreamState stream) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        InputStream inputStream = stream.socket.getInputStream();
        while (!stopped.get() && !stream.closed.get()) {
            int read = inputStream.read(buffer);
            if (read == -1) {
                break;
            }
            byte[] payload = new byte[read];
            System.arraycopy(buffer, 0, payload, 0, read);
            sendFrame(FRAME_DATA, stream.streamId, payload);
        }
        closeStream(stream.streamId, true);
    }

    private void sendOpenError(int streamId, String message) {
        try {
            sendFrame(FRAME_OPEN_ERROR, streamId, message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            closeCurrentSocket();
        }
    }

    private void closeStream(int streamId, boolean notifyGateway) {
        StreamState stream = streams.remove(streamId);
        if (stream != null) {
            stream.close();
        }
        if (notifyGateway && !stopped.get()) {
            try {
                sendFrame(FRAME_CLOSE, streamId, new byte[0]);
            } catch (IOException ignored) {
                closeCurrentSocket();
            }
        }
    }

    private void closeAllStreams() {
        for (Integer streamId : streams.keySet()) {
            closeStream(streamId.intValue(), false);
        }
        streams.clear();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("ProxyTunnelHeartbeat"));
        heartbeatExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (stopped.get()) {
                    return;
                }
                try {
                    sendFrame(FRAME_PING, 0, new byte[0]);
                } catch (IOException ignored) {
                    closeCurrentSocket();
                }
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private void sendFrame(byte type, int streamId, byte[] payload) throws IOException {
        DataOutputStream output = tunnelOutput;
        if (output == null) {
            throw new IOException("Tunnel output is unavailable.");
        }
        if (payload == null) {
            payload = new byte[0];
        }
        if (payload.length > MAX_FRAME_LENGTH) {
            throw new IOException("Tunnel frame is too large.");
        }
        synchronized (tunnelWriteLock) {
            output.writeByte(type);
            output.writeInt(streamId);
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
        }
        uploadedBytes += 9L + payload.length;
        listener.onBytesChanged(uploadedBytes, downloadedBytes);
    }

    private Frame readFrame(DataInputStream input) throws IOException {
        byte type = input.readByte();
        int streamId = input.readInt();
        int length = input.readInt();
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("Invalid tunnel frame length.");
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        downloadedBytes += 9L + length;
        listener.onBytesChanged(uploadedBytes, downloadedBytes);
        return new Frame(type, streamId, payload);
    }

    private void writeAuthLine(OutputStream outputStream, String line) throws IOException {
        byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        outputStream.write(data);
        outputStream.flush();
        uploadedBytes += data.length;
        listener.onBytesChanged(uploadedBytes, downloadedBytes);
    }

    private String readLineLimited(InputStream inputStream, int maxLength) throws IOException {
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
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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

    private void closeCurrentSocket() {
        Socket socket = currentSocket;
        currentSocket = null;
        if (socket != null) {
            closeSocket(socket);
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket is being closed as part of stream/tunnel cleanup.
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

    private static final class Frame {
        final byte type;
        final int streamId;
        final byte[] payload;

        Frame(byte type, int streamId, byte[] payload) {
            this.type = type;
            this.streamId = streamId;
            this.payload = payload;
        }
    }

    private static final class Target {
        final String host;
        final int port;

        Target(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static Target parse(byte[] payload) throws IOException {
            String value = new String(payload, StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('\n');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IOException("Invalid target payload.");
            }
            String host = value.substring(0, separator);
            int port;
            try {
                port = Integer.parseInt(value.substring(separator + 1));
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid target port.");
            }
            if (host.length() == 0 || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0
                    || port < 1 || port > 65535) {
                throw new IOException("Invalid target.");
            }
            return new Target(host, port);
        }
    }

    private static final class StreamState {
        final int streamId;
        final Socket socket;
        final AtomicBoolean closed = new AtomicBoolean(false);

        StreamState(int streamId, Socket socket) {
            this.streamId = streamId;
            this.socket = socket;
        }

        void write(byte[] payload) throws IOException {
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(payload);
            outputStream.flush();
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                closeSocket(socket);
            }
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class AuthenticationException extends Exception {
    }
}
