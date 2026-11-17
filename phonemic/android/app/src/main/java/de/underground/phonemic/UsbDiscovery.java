package de.underground.phonemic;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Discovers an OBS receiver only on Android's USB-tether interfaces.
 * Existing UDP audio transport is unchanged; discovery is optional.
 */
public final class UsbDiscovery {
    private static final byte[] REQUEST = "PMDISC01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ANSWER = "PMDOK001".getBytes(StandardCharsets.US_ASCII);

    public static final class Result {
        public final String ip;
        public final String message;
        Result(String ip, String message) {
            this.ip = ip;
            this.message = message;
        }
    }

    private UsbDiscovery() {}

    private static boolean usbNetwork(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("rndis") || lower.startsWith("usb")
                || lower.startsWith("ncm") || lower.startsWith("tether");
    }

    private static int read32(byte[] p, int o) {
        return (p[o] & 255) | ((p[o + 1] & 255) << 8) |
                ((p[o + 2] & 255) << 16) | ((p[o + 3] & 255) << 24);
    }

    private static void write32(byte[] b, int o, int n) {
        b[o] = (byte) n;
        b[o + 1] = (byte) (n >>> 8);
        b[o + 2] = (byte) (n >>> 16);
        b[o + 3] = (byte) (n >>> 24);
    }

    public static Result discover(int port, int code) {
        boolean interfaceFound = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null)
                return new Result(null, "Keine Netzwerkschnittstellen verfügbar.");

            while (interfaces.hasMoreElements()) {
                NetworkInterface net = interfaces.nextElement();
                if (!usbNetwork(net.getName()) || !net.isUp() || net.isLoopback())
                    continue;

                for (InterfaceAddress address : net.getInterfaceAddresses()) {
                    InetAddress local = address.getAddress();
                    InetAddress broadcast = address.getBroadcast();
                    if (!(local instanceof Inet4Address))
                        continue;
                    interfaceFound = true;
                    if (!(broadcast instanceof Inet4Address))
                        continue;

                    try (DatagramSocket socket = new DatagramSocket(
                            new InetSocketAddress(local, 0))) {
                        socket.setBroadcast(true);
                        socket.setSoTimeout(200);
                        byte[] bytes = new byte[12];
                        System.arraycopy(REQUEST, 0, bytes, 0, REQUEST.length);
                        write32(bytes, 8, code);
                        DatagramPacket request = new DatagramPacket(
                                bytes, bytes.length, broadcast, port);
                        byte[] received = new byte[64];
                        DatagramPacket response = new DatagramPacket(received, received.length);

                        for (int attempt = 0; attempt < 7; attempt++) {
                            socket.send(request);
                            response.setLength(received.length);
                            try {
                                socket.receive(response);
                                if (response.getLength() == 12
                                        && response.getPort() == port
                                        && received[0] == ANSWER[0]
                                        && received[1] == ANSWER[1]
                                        && received[2] == ANSWER[2]
                                        && received[3] == ANSWER[3]
                                        && received[4] == ANSWER[4]
                                        && received[5] == ANSWER[5]
                                        && received[6] == ANSWER[6]
                                        && received[7] == ANSWER[7]
                                        && read32(received, 8) == code) {
                                    return new Result(response.getAddress().getHostAddress(),
                                            "OBS-PC über USB erkannt.");
                                }
                            } catch (SocketTimeoutException ignored) {}
                        }
                    } catch (Exception ignored) {
                        // Some Android builds do not permit interface-bound broadcast.
                        // Manual PC IPv4 entry remains available.
                    }
                }
            }
        } catch (Exception e) {
            return new Result(null, "USB-Suche fehlgeschlagen. Windows-USB-IPv4 manuell eintragen.");
        }
        if (!interfaceFound) {
            return new Result(null, "USB-Tethering nicht erkannt. Kabel verbinden und am Handy USB-Tethering aktivieren.");
        }
        return new Result(null,
                "Keine OBS-Antwort über USB. Windows-Firewall und OBS prüfen oder USB-IPv4 des PCs manuell eingeben.");
    }
}
