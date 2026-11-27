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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class Socks5Proxy extends DirectSocketFactory {
    private final AppConfig.Proxy proxy;
    private final Map<String, InetAddress> destinations;
    private final int timeoutMillis;

    Socks5Proxy(AppConfig.Proxy proxy, Set<String> hosts, int timeoutSeconds) {
        this.proxy = proxy;
        this.timeoutMillis = Math.toIntExact(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        var addresses = new HashMap<String, InetAddress>();
        int index = 0;
        for (String host : hosts) {
            // Distinct synthetic addresses prevent HTTP/2 connection coalescing between SOCKS destinations.
            byte[] address = {0x20, 1, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            java.nio.ByteBuffer.wrap(address).putInt(12, ++index);
            try {
                addresses.put(host, InetAddress.getByAddress(host, address));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid synthetic SOCKS address");
            }
        }
        this.destinations = Map.copyOf(addresses);
    }

    Dns remoteDns() {
        return host -> {
            InetAddress address = destinations.get(host);
            if (address == null) throw new UnknownHostException("Unexpected SOCKS5 destination");
            return List.of(address);
        };
    }

    @Override
    public Socket createSocket() {
        return new Socket(Proxy.NO_PROXY) {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (!(endpoint instanceof InetSocketAddress target) || !destinations.containsKey(target.getHostString())) {
                    throw new IOException("Unexpected SOCKS5 destination");
                }
                int budget = timeout == 0 ? timeoutMillis : Math.min(timeout, timeoutMillis);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
                int originalTimeout = getSoTimeout();
                try {
                    super.connect(new InetSocketAddress(proxy.host(), proxy.port()), remaining(deadline));
                    negotiate(this, target.getHostString(), target.getPort(), deadline);
                    setSoTimeout(originalTimeout);
                } catch (IOException e) {
                    close();
                    throw e;
                }
            }
        };
    }

    private void negotiate(Socket socket, String host, int port, long deadline) throws IOException {
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
        byte[] targetBytes = host.getBytes(StandardCharsets.US_ASCII);
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
