package com.example.demo;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

public interface Wintun extends StdCallLibrary {

    Wintun INSTANCE =
            Native.load("wintun", Wintun.class);

    Pointer WintunCreateAdapter(
            WString name,
            WString tunnelType,
            Pointer requestedGuid
    );

    Pointer WintunOpenAdapter(
            WString name
    );

    void WintunCloseAdapter(
            Pointer adapter
    );

    void WintunGetAdapterLUID(
            Pointer adapter,
            byte[] luid
    );

    int WintunGetRunningDriverVersion();

    Pointer WintunStartSession(
            Pointer adapter,
            int capacity
    );

    void WintunEndSession(
            Pointer session
    );

    Pointer WintunGetReadWaitEvent(
            Pointer session
    );

    Pointer WintunReceivePacket(
            Pointer session,
            IntByReference packetSize
    );

    void WintunReleaseReceivePacket(
            Pointer session,
            Pointer packet
    );

    Pointer WintunAllocateSendPacket(
            Pointer session,
            int packetSize
    );

    void WintunSendPacket(
            Pointer session,
            Pointer packet
    );
}
