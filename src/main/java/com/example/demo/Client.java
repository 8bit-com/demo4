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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Client {

    private static final String ADAPTER_NAME = "MyVPN";
    private static final String ADAPTER_TYPE = "VPN";

    private static final String ADAPTER_IP = "10.8.0.2";
    private static final String SERVER_IP = "80.240.23.72";
    private static final String SERVER_URL = "http://" + SERVER_IP + ":8080";

    private static final int WINTUN_RING_CAPACITY = 0x400000;

    private final ExecutorService txWorkers = Executors.newFixedThreadPool(20);
    private final AtomicLong txCounter = new AtomicLong();
    private final AtomicLong rxCounter = new AtomicLong();


    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        RouteManager routeManager =
                new RouteManager(ADAPTER_NAME, ADAPTER_IP, SERVER_IP);

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    txWorkers.shutdownNow();
                    routeManager.stop();
                })
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
            System.out.println("ping 1.1.1.1");

            Pointer currentSession = session;
            for (int i = 0; i < 20; i++) {
                Thread rxThread = new Thread(() -> rxLoop(currentSession), "http-rx-to-tun-" + i);
                rxThread.setDaemon(true);
                rxThread.start();
            }

            tunToHttpTx(session);

        } finally {
            txWorkers.shutdownNow();
            routeManager.stop();

            if (session != null) {
                Wintun.INSTANCE.WintunEndSession(session);
            }

            if (adapter != null) {
                Wintun.INSTANCE.WintunCloseAdapter(adapter);
            }
        }
    }

    private void tunToHttpTx(Pointer session) throws Exception {

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

            long id = txCounter.incrementAndGet();
            //System.out.println("TUN -> TX id=" + id + " " + data.length + " bytes " + ipInfo(data));

            byte[] requestPacket = data;

            txWorkers.submit(() -> sendPacketToServerTx(id, requestPacket));
        }
    }

    private void sendPacketToServerTx(long id, byte[] packet) {
        long start = System.nanoTime();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER_URL + "/tx"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(50))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(packet))
                    .build();

            HttpClient httpClient2 = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(50))
                    .build();

            HttpResponse<Void> response =
                    httpClient2.send(request, HttpResponse.BodyHandlers.discarding());

            int code = response.statusCode();
            if (code != 204 && code != 200) {
                System.out.println("TX STATUS id=" + id + " " + code + " after " + ms(start, System.nanoTime()) + "ms");
            }

        } catch (Exception e) {
            System.out.println("TX ERROR id=" + id + " after " + ms(start, System.nanoTime()) + "ms: " + e.getMessage());
        }
    }

    private void rxLoop(Pointer session) {
        while (true) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SERVER_URL + "/rx"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(Duration.ofSeconds(50))
                        .GET()
                        .build();

                HttpClient httpClient = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(50))
                        .build();

                HttpResponse<byte[]> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() == 204) {
                    continue;
                }

                if (response.statusCode() != 200) {
                    System.out.println("RX STATUS " + response.statusCode());
                    Thread.sleep(1);
                    continue;
                }

                byte[] packet = response.body();
                if (!isIpv4(packet)) {
                    continue;
                }

                long id = rxCounter.incrementAndGet();
                //System.out.println("RX -> TUN id=" + id + " " + packet.length + " bytes " + ipInfo(packet));
                writeToTun(session, packet, id);

            } catch (Exception e) {
                System.out.println("RX ERROR: " + e.getMessage());
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private long ms(long from, long to) {
        return (to - from) / 1_000_000;
    }

    private void writeToTun(Pointer session, byte[] data, long id) {

        Pointer sendPacket = Wintun.INSTANCE.WintunAllocateSendPacket(
                session,
                data.length
        );

        if (sendPacket == null) {
            System.out.println("Cannot allocate Wintun send packet id=" + id);
            return;
        }

        sendPacket.write(0, data, 0, data.length);

        Wintun.INSTANCE.WintunSendPacket(session, sendPacket);

        //System.out.println("WRITTEN TO WINTUN id=" + id + " " + data.length + " bytes " + ipInfo(data));
    }

    private boolean isIpv4(byte[] packet) {
        return packet.length >= 20 && ((packet[0] >> 4) & 0x0F) == 4;
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
