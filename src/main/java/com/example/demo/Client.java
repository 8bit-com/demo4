package com.example.demo;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class Client {

    private static final String ADAPTER_NAME = "MyVPN";
    private static final String ADAPTER_TYPE = "VPN";

    private static final String ADAPTER_IP = "10.33.67.1";
    private static final String TARGET_IP = "10.33.67.2";
    private static final String SERVER_IP = "80.240.23.72";

    private static final int WINTUN_RING_CAPACITY = 0x400000;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        RouteManager routeManager =
                new RouteManager(ADAPTER_NAME, ADAPTER_IP, SERVER_IP);

        Pointer adapter = null;
        Pointer session = null;

        Runtime.getRuntime().addShutdownHook(
                new Thread(routeManager::stop)
        );

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

            while (true) {
                IntByReference size = new IntByReference();

                Pointer packet = Wintun.INSTANCE.WintunReceivePacket(session, size);

                if (packet == null) {
                    Thread.sleep(1);
                    continue;
                }

                byte[] data = packet.getByteArray(0, size.getValue());

                Wintun.INSTANCE.WintunReleaseReceivePacket(session, packet);

                byte[] response = buildIcmpReply(data);

                if (response != null) {
                    sendPacket(session, response);
                }
            }

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

    private byte[] buildIcmpReply(byte[] request) {

        if (request.length < 20) {
            return null;
        }

        int version = (request[0] >> 4) & 0x0F;

        if (version != 4) {
            return null;
        }

        int protocol = request[9] & 0xFF;

        if (protocol != 1) {
            return null;
        }

        String src = ip(request, 12);
        String dst = ip(request, 16);


        int ipHeaderLength = (request[0] & 0x0F) * 4;

        if (request.length < ipHeaderLength + 8) {
            return null;
        }

        int icmpType = request[ipHeaderLength] & 0xFF;

        if (icmpType != 8) {
            return null;
        }

        System.out.println("PING REQUEST: " + src + " -> " + dst);

        byte[] reply = Arrays.copyOf(request, request.length);

        reply[ipHeaderLength] = 0;
        reply[ipHeaderLength + 1] = 0;

        for (int i = 0; i < 4; i++) {
            byte tmp = reply[12 + i];
            reply[12 + i] = reply[16 + i];
            reply[16 + i] = tmp;
        }

        reply[8] = 64;

        reply[10] = 0;
        reply[11] = 0;
        int ipChecksum = checksum(reply, 0, ipHeaderLength);
        reply[10] = (byte) ((ipChecksum >> 8) & 0xFF);
        reply[11] = (byte) (ipChecksum & 0xFF);

        reply[ipHeaderLength + 2] = 0;
        reply[ipHeaderLength + 3] = 0;

        int totalLength = ((reply[2] & 0xFF) << 8) | (reply[3] & 0xFF);
        int icmpLength = totalLength - ipHeaderLength;

        int icmpChecksum = checksum(reply, ipHeaderLength, icmpLength);
        reply[ipHeaderLength + 2] = (byte) ((icmpChecksum >> 8) & 0xFF);
        reply[ipHeaderLength + 3] = (byte) (icmpChecksum & 0xFF);

        System.out.println("PING REPLY: " + TARGET_IP + " -> " + ADAPTER_IP);

        return reply;
    }

    private void sendPacket(Pointer session, byte[] data) {

        Pointer sendPacket = Wintun.INSTANCE.WintunAllocateSendPacket(
                session,
                data.length
        );

        if (sendPacket == null) {
            System.out.println("Cannot allocate send packet");
            return;
        }

        sendPacket.write(0, data, 0, data.length);

        Wintun.INSTANCE.WintunSendPacket(session, sendPacket);
    }

    private int checksum(byte[] data, int offset, int length) {

        long sum = 0;

        int i = offset;

        while (length > 1) {
            int word =
                    ((data[i] & 0xFF) << 8) |
                            (data[i + 1] & 0xFF);

            sum += word;

            if ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >> 16);
            }

            i += 2;
            length -= 2;
        }

        if (length > 0) {
            sum += (data[i] & 0xFF) << 8;

            if ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >> 16);
            }
        }

        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFFL) + (sum >> 16);
        }

        return (int) (~sum) & 0xFFFF;
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xFF) + "." +
                (data[offset + 1] & 0xFF) + "." +
                (data[offset + 2] & 0xFF) + "." +
                (data[offset + 3] & 0xFF);
    }
}