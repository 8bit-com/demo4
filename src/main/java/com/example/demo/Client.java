package com.example.demo;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class Client {

    private static final String ADAPTER_NAME = "MyVPN";
    private static final String ADAPTER_TYPE = "VPN";

    private static final String ADAPTER_IP = "10.8.0.2";
    private static final String SERVER_IP = "80.240.23.72";
    private static final String SERVER_URL = "http://" + SERVER_IP + ":8080";

    private static final int WINTUN_RING_CAPACITY = 0x400000;

    private long requestId = 0;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        RouteManager routeManager =
                new RouteManager(ADAPTER_NAME, ADAPTER_IP, SERVER_IP);

        Runtime.getRuntime().addShutdownHook(
                new Thread(routeManager::stop)
        );

        Pointer adapter = null;
        Pointer session = null;

        try {
            adapter = Wintun.INSTANCE.WintunCreateAdapter(
                    new WString(ADAPTER_NAME),
                    new WString(ADAPTER_TYPE),
                    null
            );

            if (adapter == null) {
                adapter = Wintun.INSTANCE.WintunOpenAdapter(
                        new WString(ADAPTER_NAME)
                );
            }

            if (adapter == null) {
                throw new RuntimeException("Cannot create/open adapter: " + ADAPTER_NAME);
            }

            System.out.println("Adapter ready: " + ADAPTER_NAME);

            routeManager.start();

            session = Wintun.INSTANCE.WintunStartSession(
                    adapter,
                    WINTUN_RING_CAPACITY
            );

            if (session == null) {
                throw new RuntimeException("Cannot start Wintun session");
            }

            System.out.println("Session started");
            System.out.println("Run:");
            System.out.println("ping 10.8.0.1");

            tunToHttpPacket(session);

        } finally {
            routeManager.stop();

            if (session != null) {
                Wintun.INSTANCE.WintunEndSession(session);
            }

            if (adapter != null) {
                Wintun.INSTANCE.WintunCloseAdapter(adapter);
            }
        }
    }

    private void tunToHttpPacket(Pointer session) throws Exception {

        while (true) {
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

            if (!isIpv4(data)) {
                continue;
            }

            if (!isIcmp(data)) {
                continue;
            }

            System.out.println("TUN -> HTTP " + data.length + " bytes " + ipInfo(data));

            byte[] response = sendPacketToServer(data);

            if (response == null) {
                continue;
            }

            if (!isIpv4(response)) {
                continue;
            }

            System.out.println("HTTP -> TUN " + response.length + " bytes " + ipInfo(response));

            writeToTun(session, response);
        }
    }

    private byte[] sendPacketToServer(byte[] packet) {

        long start = System.nanoTime();

        try {
            ++requestId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER_URL + "/packet?id=" + requestId))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(packet))
                    .build();

            long beforeSend = System.nanoTime();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            long afterSend = System.nanoTime();

            int code = response.statusCode();

            System.out.println("HTTP STATUS id=" + requestId + " " + code + " send=" + ms(beforeSend, afterSend) + "ms");

            if (code == 200) {
                return response.body();
            }

            return null;

        } catch (Exception e) {
            long errorTime = System.nanoTime();

            System.out.println("HTTP ERROR id=" + requestId + " after " + ms(start, errorTime) + "ms: " + e.getMessage());

            return null;
        }
    }

    private long ms(long from, long to) {
        return (to - from) / 1_000_000;
    }

    private void writeToTun(Pointer session, byte[] data) {

        Pointer sendPacket = Wintun.INSTANCE.WintunAllocateSendPacket(
                session,
                data.length
        );

        if (sendPacket == null) {
            System.out.println("Cannot allocate Wintun send packet");
            return;
        }

        sendPacket.write(0, data, 0, data.length);

        Wintun.INSTANCE.WintunSendPacket(session, sendPacket);

        System.out.println("WRITTEN TO WINTUN " + data.length + " bytes " + ipInfo(data));
    }

    private boolean isIpv4(byte[] packet) {
        return packet.length >= 20 && ((packet[0] >> 4) & 0x0F) == 4;
    }

    private boolean isIcmp(byte[] packet) {
        return packet.length >= 20 && (packet[9] & 0xFF) == 1;
    }

    private String ipInfo(byte[] packet) {
        if (packet.length < 20) {
            return "";
        }

        String src = ip(packet, 12);
        String dst = ip(packet, 16);
        int protocol = packet[9] & 0xFF;

        return src + " -> " + dst + " proto=" + protocol;
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xFF) + "." +
                (data[offset + 1] & 0xFF) + "." +
                (data[offset + 2] & 0xFF) + "." +
                (data[offset + 3] & 0xFF);
    }
}