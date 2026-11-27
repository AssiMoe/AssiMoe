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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UsbDiscovery {
    private static final byte[] REQUEST = "PMDISC01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ANSWER = "PMDOK001".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_TARGETS_PER_INTERFACE = 256;
    private static final int BATCH_SIZE = 24;
    private static final int RECV_WAIT_MS = 15;

    public static final class Result {
        public final String ip;
        public final String message;
        public final String diagnostics;

        Result(String ip, String message, String diagnostics) {
            this.ip = ip;
            this.message = message;
            this.diagnostics = diagnostics;
        }
    }

    private UsbDiscovery() {}

    private static boolean usbNetwork(String name, String displayName) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT);
        String display = (displayName == null ? "" : displayName).toLowerCase(Locale.ROOT);
        // Android manufacturers use different RNDIS / NCM interface names.
        return n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")
                || n.startsWith("tether") || n.startsWith("eth") || n.startsWith("enx")
                || display.contains("rndis") || display.contains("tether")
                || display.contains("usb ethernet") || display.contains("usb ncm");
    }

    private static boolean validPrivate(Inet4Address ip) {
        byte[] b = ip.getAddress();
        int a = b[0] & 255;
        int c = b[1] & 255;
        if (ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isMulticastAddress())
            return false;
        return a == 10 || (a == 172 && c >= 16 && c <= 31)
                || (a == 192 && c == 168);
    }

    private static int toInt(Inet4Address ip) {
        byte[] b = ip.getAddress();
        return ((b[0] & 255) << 24) | ((b[1] & 255) << 16)
                | ((b[2] & 255) << 8) | (b[3] & 255);
    }

    private static InetAddress fromInt(int n) throws Exception {
        return InetAddress.getByAddress(new byte[]{
                (byte)(n >>> 24), (byte)(n >>> 16), (byte)(n >>> 8), (byte)n
        });
    }

    private static void write32(byte[] buffer, int off, int value) {
        buffer[off] = (byte) value;
        buffer[off + 1] = (byte) (value >>> 8);
        buffer[off + 2] = (byte) (value >>> 16);
        buffer[off + 3] = (byte) (value >>> 24);
    }

    private static int read32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8)
                | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private static InetAddress receiveValid(DatagramSocket socket,
                                             DatagramPacket response,
                                             int port, int code, int waitMs) {
        try {
            response.setLength(response.getData().length);
            socket.setSoTimeout(waitMs);
            socket.receive(response);
            byte[] buf = response.getData();
            int offset = response.getOffset();
            if (response.getLength() != 12 || response.getPort() != port)
                return null;
            for (int i = 0; i < ANSWER.length; i++) {
                if (buf[offset + i] != ANSWER[i])
                    return null;
            }
            if (read32(buf, offset + 8) != code)
                return null;
            if (!(response.getAddress() instanceof Inet4Address))
                return null;
            return response.getAddress();
        } catch (SocketTimeoutException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Result scanAddress(Inet4Address local,
                                      InterfaceAddress interfaceAddress,
                                      int port, int code, String label) {
        int address = toInt(local);
        int prefix = interfaceAddress.getNetworkPrefixLength();
        if (prefix <= 0 || prefix > 30)
            prefix = 24;
        // A subnet larger than /24 is deliberately restricted to the local /24.
        int effectivePrefix = Math.max(24, prefix);
        int mask = -1 << (32 - effectivePrefix);
        int network = address & mask;
        int broadcast = network | ~mask;

        Set<Integer> targets = new LinkedHashSet<>();
        if (interfaceAddress.getBroadcast() instanceof Inet4Address)
            targets.add(toInt((Inet4Address) interfaceAddress.getBroadcast()));
        targets.add(broadcast);

        // These are only priorities, not assumed PC addresses.
        for (int suffix : new int[]{1, 2, 10, 100, 129, 254}) {
            int candidate = (address & 0xFFFFFF00) | suffix;
            if (candidate != address && candidate > network && candidate < broadcast)
                targets.add(candidate);
        }
        for (int candidate = network + 1;
             candidate != broadcast && targets.size() < MAX_TARGETS_PER_INTERFACE;
             candidate++) {
            if (candidate != address)
                targets.add(candidate);
        }

        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(local, 0))) {
            socket.setBroadcast(true);
            socket.setSendBufferSize(64 * 1024);
            socket.setReceiveBufferSize(64 * 1024);
            byte[] payload = new byte[12];
            System.arraycopy(REQUEST, 0, payload, 0, REQUEST.length);
            write32(payload, 8, code);
            byte[] received = new byte[64];
            DatagramPacket response = new DatagramPacket(received, received.length);

            for (int pass = 0; pass < 2; pass++) {
                int sent = 0;
                for (Integer ip : targets) {
                    if (Thread.currentThread().isInterrupted())
                        return new Result(null, "USB-Suche abgebrochen.", label);
                    InetAddress candidate = fromInt(ip);
                    try {
                        DatagramPacket request = new DatagramPacket(payload, payload.length, candidate, port);
                        socket.send(request);
                    } catch (Exception ignored) {
                        continue;
                    }
                    sent++;
                    if (sent % BATCH_SIZE == 0) {
                        InetAddress found = receiveValid(socket, response, port, code, RECV_WAIT_MS);
                        if (found != null)
                            return new Result(found.getHostAddress(), "OBS-PC über USB erkannt.", label);
                    }
                }
                // Give OBS enough time to answer even if its receive queue was busy.
                for (int i = 0; i < 12; i++) {
                    InetAddress found = receiveValid(socket, response, port, code, 40);
                    if (found != null)
                        return new Result(found.getHostAddress(), "OBS-PC über USB erkannt.", label);
                }
            }
        } catch (Exception exc) {
            return new Result(null, "USB-Netzwerk konnte nicht abgefragt werden.", label
                    + ": " + exc.getClass().getSimpleName());
        }
        return new Result(null, "Keine OBS-Antwort auf dem USB-Netzwerk.", label);
    }

    public static Result discover(int port, int code) {
        List<String> networks = new ArrayList<>();
        int scanned = 0;
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null)
                return new Result(null, "Keine Android-Netzwerkschnittstellen gefunden.", "");

            while (all.hasMoreElements()) {
                NetworkInterface net = all.nextElement();
                if (!usbNetwork(net.getName(), net.getDisplayName())
                        || !net.isUp() || net.isLoopback())
                    continue;

                for (InterfaceAddress entry : net.getInterfaceAddresses()) {
                    if (!(entry.getAddress() instanceof Inet4Address))
                        continue;
                    Inet4Address local = (Inet4Address) entry.getAddress();
                    if (!validPrivate(local))
                        continue;
                    scanned++;
                    String label = net.getName() + " (" + local.getHostAddress()
                            + "/" + entry.getNetworkPrefixLength() + ")";
                    networks.add(label);
                    Result result = scanAddress(local, entry, port, code, label);
                    if (result.ip != null)
                        return result;
                }
            }
        } catch (Exception exc) {
            return new Result(null, "USB-Suche fehlgeschlagen: "
                    + exc.getClass().getSimpleName(), String.join(", ", networks));
        }

        if (scanned == 0) {
            return new Result(null,
                    "Keine aktive USB-Netzwerkschnittstelle erkannt. USB-Tethering am Handy aktivieren.",
                    "Android: keine RNDIS/NCM/USB-Ethernet IPv4. "
                            + "Prüfe USB-Datenkabel und USB-Tethering.");
        }
        return new Result(null,
                "USB-Netzwerk erkannt, aber OBS antwortet nicht. "
                        + "OBS-Quelle, Port, Verbindungscode und Windows-Firewall prüfen.",
                String.join(", ", networks));
    }
}
