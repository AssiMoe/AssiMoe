package de.underground.phonemic;

import java.net.DatagramPacket;
import java.util.Arrays;

public final class MicProtocol {
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAMES_PER_PACKET = 480;
    public static final int PCM_BYTES = FRAMES_PER_PACKET * 2;
    public static final int HEADER_BYTES = 20;
    public static final int PACKET_BYTES = HEADER_BYTES + PCM_BYTES;
    private MicProtocol() {}
    public static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }
    public static int get32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) |
            ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }
    public static byte[] newFrame(int code, int session) {
        byte[] b = new byte[PACKET_BYTES];
        b[0] = 'A'; b[1] = 'M'; b[2] = 'I'; b[3] = 'C';
        b[4] = 1;
        b[6] = (byte) PCM_BYTES;
        b[7] = (byte) (PCM_BYTES >>> 8);
        put32(b, 12, code);
        put32(b, 16, session);
        return b;
    }
    public static void setSequence(byte[] b, int sequence) { put32(b, 8, sequence); }
    public static boolean isAck(DatagramPacket p, int code, int session) {
        if (p.getLength() != 12) return false;
        byte[] b = p.getData();
        int o = p.getOffset();
        return b[o] == 'A' && b[o+1] == 'M' && b[o+2] == 'O' && b[o+3] == 'K' &&
               get32(b, o+4) == code && get32(b, o+8) == session;
    }
    public static void applyGain(byte[] p, int percent, boolean mute) {
        if (mute) { Arrays.fill(p, HEADER_BYTES, p.length, (byte)0); return; }
        if (percent == 100) return;
        for (int i = HEADER_BYTES; i < p.length; i += 2) {
            int s = (short)((p[i] & 255) | (p[i+1] << 8));
            s = Math.max(-32768, Math.min(32767, s * percent / 100));
            p[i] = (byte)s;
            p[i+1] = (byte)(s >>> 8);
        }
    }
}
