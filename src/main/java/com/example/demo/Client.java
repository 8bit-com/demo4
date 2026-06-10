package com.example.demo;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Client {

    private static final String ADAPTER_NAME = "MyVPN";
    private static final String ADAPTER_TYPE = "VPN";

    private static final String ADAPTER_IP = "10.8.0.2";
    private static final String SERVER_IP = "80.240.23.72";
    private static final String SERVER_WS_URL = "ws://" + SERVER_IP + ":18080";

    private static final int WINTUN_RING_CAPACITY = 0x400000;
    private static final int MAX_PACKET_SIZE = 65535;
    private static final long LOG_FIRST_PACKETS = 30;
    private static final long LOG_EVERY_PACKETS = 500;

    private final AtomicLong txCounter = new AtomicLong();
    private final AtomicLong rxCounter = new AtomicLong();
    private final AtomicBoolean wsClosed = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        RouteManager routeManager = new RouteManager(ADAPTER_NAME, ADAPTER_IP, SERVER_IP);

        Pointer adapter = null;
        Pointer session = null;
        WebSocketClient wsClient = null;

        try {
            adapter = Wintun.INSTANCE.WintunCreateAdapter(
                    new WString(ADAPTER_NAME),
                    new WString(ADAPTER_TYPE),
                    null
            );

            if (adapter == null) {
                adapter = Wintun.INSTANCE.WintunOpenAdapter(new WString(ADAPTER_NAME));
            }

            if (adapter == null) {
                throw new RuntimeException("Cannot create/open adapter: " + ADAPTER_NAME);
            }

            System.out.println("Adapter ready: " + ADAPTER_NAME);

            session = Wintun.INSTANCE.WintunStartSession(adapter, WINTUN_RING_CAPACITY);
            if (session == null) {
                throw new RuntimeException("Cannot start Wintun session");
            }

            System.out.println("Session started");

            routeManager.start();

            System.out.println("WS URL: " + SERVER_WS_URL);

            Pointer currentSession = session;
            CountDownLatch connected = new CountDownLatch(1);

            wsClient = new WebSocketClient(new URI(SERVER_WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    System.out.println("WS connected");
                    connected.countDown();
                }

                @Override
                public void onMessage(String message) {
                    System.out.println("WS text ignored: " + message);
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    byte[] packet = new byte[bytes.remaining()];
                    bytes.get(packet);

                    if (!isIpv4(packet)) {
                        System.out.println("WS -> TUN skip non-ipv4 len=" + packet.length + " first=" + firstBytes(packet));
                        return;
                    }

                    long id = rxCounter.incrementAndGet();
                    logPacket("WS -> TUN", id, packet);
                    writeToTun(currentSession, packet, id);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    wsClosed.set(true);
                    System.out.println("WS closed: code=" + code + " remote=" + remote + " reason=" + reason);
                }

                @Override
                public void onError(Exception ex) {
                    System.out.println("WS error: " + ex.getClass().getName() + ": " + ex.getMessage());
                    ex.printStackTrace();
                }
            };

            wsClient.connect();
            if (!connected.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("WS connect timeout: " + SERVER_WS_URL);
            }

            tunToWs(session, wsClient);

        } finally {
            if (wsClient != null) {
                wsClient.close();
            }

            routeManager.stop();

            if (session != null) {
                Wintun.INSTANCE.WintunEndSession(session);
            }

            if (adapter != null) {
                Wintun.INSTANCE.WintunCloseAdapter(adapter);
            }
        }
    }

    private void tunToWs(Pointer session, WebSocketClient wsClient) throws Exception {
        while (true) {
            if (wsClosed.get()) {
                throw new RuntimeException("WebSocket closed");
            }

            IntByReference size = new IntByReference();
            Pointer packet = Wintun.INSTANCE.WintunReceivePacket(session, size);

            if (packet == null) {
                Thread.sleep(1);
                continue;
            }

            byte[] data;
            try {
                data = packet.getByteArray(0, size.getValue());
            } finally {
                Wintun.INSTANCE.WintunReleaseReceivePacket(session, packet);
            }

            data = normalizeWintunPacket(data);

            if (!isIpv4(data) || data.length > MAX_PACKET_SIZE) {
                System.out.println("TUN -> WS skip invalid packet len=" + data.length + " first=" + firstBytes(data));
                continue;
            }

            if (!wsClient.isOpen()) {
                throw new RuntimeException("WebSocket is not open");
            }

            long id = txCounter.incrementAndGet();
            logPacket("TUN -> WS", id, data);
            wsClient.send(data);
        }
    }

    private byte[] normalizeWintunPacket(byte[] data) {
        if (isIpv4(data)) {
            return data;
        }

        if (data.length > 4 && isIpv4At(data, 4)) {
            return Arrays.copyOfRange(data, 4, data.length);
        }

        if (data.length > 14 && isIpv4At(data, 14)) {
            return Arrays.copyOfRange(data, 14, data.length);
        }

        return data;
    }

    private void writeToTun(Pointer session, byte[] data, long id) {
        Pointer sendPacket = Wintun.INSTANCE.WintunAllocateSendPacket(session, data.length);

        if (sendPacket == null) {
            System.out.println("Cannot allocate Wintun send packet id=" + id);
            return;
        }

        sendPacket.write(0, data, 0, data.length);
        Wintun.INSTANCE.WintunSendPacket(session, sendPacket);
    }

    private void logPacket(String direction, long id, byte[] packet) {
        if (id <= LOG_FIRST_PACKETS || id % LOG_EVERY_PACKETS == 0) {
            System.out.println(direction + " id=" + id + " len=" + packet.length + " " + ipInfo(packet));
        }
    }

    private boolean isIpv4(byte[] packet) {
        return isIpv4At(packet, 0);
    }

    private boolean isIpv4At(byte[] packet, int offset) {
        return packet.length >= offset + 20 && ((packet[offset] >> 4) & 0x0F) == 4;
    }

    private String ipInfo(byte[] packet) {
        if (packet.length < 20) {
            return "";
        }

        return ip(packet, 12) + " -> " + ip(packet, 16) + " proto=" + (packet[9] & 0xff);
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xff) + "." +
                (data[offset + 1] & 0xff) + "." +
                (data[offset + 2] & 0xff) + "." +
                (data[offset + 3] & 0xff);
    }

    private String firstBytes(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(data.length, 16);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", data[i] & 0xff));
        }
        return sb.toString();
    }
}
