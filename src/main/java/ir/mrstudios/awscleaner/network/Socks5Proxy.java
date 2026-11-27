package ir.mrstudios.awscleaner.network;

import ir.mrstudios.awscleaner.config.AppConfig;
import okhttp3.Dns;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class Socks5Proxy extends DirectSocketFactory {
    private final AppConfig.Proxy proxy;
    private final String targetHost;
    private final byte[] targetBytes;
    private final int timeoutMillis;

    Socks5Proxy(AppConfig.Proxy proxy, String targetHost, int timeoutSeconds) {
        this.proxy = proxy;
        this.targetHost = targetHost;
        this.targetBytes = targetHost.getBytes(StandardCharsets.US_ASCII);
        this.timeoutMillis = Math.toIntExact(TimeUnit.SECONDS.toMillis(timeoutSeconds));
    }

    Dns remoteDns() {
        return host -> {
            if (!targetHost.equals(host)) throw new UnknownHostException("Unexpected SOCKS5 destination");
            return List.of(InetAddress.getByAddress(host, new byte[]{(byte) 192, 0, 2, 1}));
        };
    }

    @Override
    public Socket createSocket() {
        return new Socket(Proxy.NO_PROXY) {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (!(endpoint instanceof InetSocketAddress target) || !targetHost.equals(target.getHostString())) {
                    throw new IOException("Unexpected SOCKS5 destination");
                }
                int budget = timeout == 0 ? timeoutMillis : Math.min(timeout, timeoutMillis);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
                int originalTimeout = getSoTimeout();
                try {
                    super.connect(new InetSocketAddress(proxy.host(), proxy.port()), remaining(deadline));
                    negotiate(this, target.getPort(), deadline);
                    setSoTimeout(originalTimeout);
                } catch (IOException e) {
                    close();
                    throw e;
                }
            }
        };
    }

    private void negotiate(Socket socket, int port, long deadline) throws IOException {
        var in = new DataInputStream(socket.getInputStream());
        var out = new DataOutputStream(socket.getOutputStream());
        int method = proxy.username().isEmpty() ? 0 : 2;
        out.write(new byte[]{5, 1, (byte) method});
        out.flush();
        if (read(socket, in, deadline) != 5 || read(socket, in, deadline) != method) {
            throw new IOException("SOCKS5 proxy rejected the configured authentication method");
        }
        if (method == 2) {
            byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
            byte[] password = proxy.password().getBytes(StandardCharsets.UTF_8);
            out.writeByte(1);
            out.writeByte(user.length);
            out.write(user);
            out.writeByte(password.length);
            out.write(password);
            out.flush();
            if (read(socket, in, deadline) != 1 || read(socket, in, deadline) != 0) {
                throw new IOException("SOCKS5 authentication failed");
            }
        }
        out.write(new byte[]{5, 1, 0, 3, (byte) targetBytes.length});
        out.write(targetBytes);
        out.writeShort(port);
        out.flush();
        if (read(socket, in, deadline) != 5) throw new IOException("Invalid SOCKS5 reply");
        int reply = read(socket, in, deadline);
        if (reply != 0) throw new IOException("SOCKS5 CONNECT rejected with status " + reply);
        if (read(socket, in, deadline) != 0) throw new IOException("Invalid SOCKS5 reply");
        int length = switch (read(socket, in, deadline)) {
            case 1 -> 4;
            case 3 -> read(socket, in, deadline);
            case 4 -> 16;
            default -> throw new IOException("Invalid SOCKS5 address type");
        };
        for (int i = 0; i < length + 2; i++) read(socket, in, deadline);
    }

    private static int read(Socket socket, DataInputStream input, long deadline) throws IOException {
        socket.setSoTimeout(remaining(deadline));
        return input.readUnsignedByte();
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("SOCKS5 negotiation timed out");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }
}
