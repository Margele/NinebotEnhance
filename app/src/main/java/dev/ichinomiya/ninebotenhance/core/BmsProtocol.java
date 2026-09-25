package dev.ichinomiya.ninebotenhance.core;

import java.util.List;

/**
 * A protection board protocol the module can talk to. Only read commands are ever sent: the board is addressed through one
 * of its advertised service and characteristic pairs, asked for its status on the configured interval and its notifications
 * assembled one frame at a time into a reading. Every board is normalised to this module's sign convention where negative
 * current and power mean charging, so {@link BmsData#charging()} holds for all of them.
 */
public interface BmsProtocol {
    /** The 16-bit services every board in the family lives on, with the base 00000000-0000-1000-8000-00805f9b34fb. */
    String FFE0="0000ffe0-0000-1000-8000-00805f9b34fb",FFE1="0000ffe1-0000-1000-8000-00805f9b34fb";
    String FF00="0000ff00-0000-1000-8000-00805f9b34fb",FF01="0000ff01-0000-1000-8000-00805f9b34fb",FF02="0000ff02-0000-1000-8000-00805f9b34fb";
    String NUS_SERVICE="6e400001-b5a3-f393-e0a9-e50e24dcca9e",NUS_WRITE="6e400002-b5a3-f393-e0a9-e50e24dcca9e",NUS_NOTIFY="6e400003-b5a3-f393-e0a9-e50e24dcca9e";
    /** One BmsSettings.PROTOCOL_* id. */
    int id();
    /** Service and characteristic pairs in the order the board prefers them; the first one the device exposes wins. */
    List<Endpoint> endpoints();
    /** Frames sent once the notifications are live. */
    byte[][] begin(long now);
    /** Frames sent on the next poll tick; empty while the board pushes its status by itself. */
    byte[][] poll(long now,long lastDataAt);
    /** An extra frame sent a moment after begin or poll; null when the board needs none. */
    default byte[] followUp(long now,long lastDataAt){return null;}
    /** Delay before that extra frame, in milliseconds. */
    default long followUpDelayMs(){return 0;}
    /** How long a silent link is kept before it is dropped. */
    long silenceMs(int pollMs);
    /** One notification; the reading once a whole frame has arrived. */
    BmsData accept(byte[] value,long now);
    void reset();
    /** Where a board keeps its control channel: an exact pair, or any writable and any notifiable characteristic. */
    record Endpoint(String service,String write,String notification,boolean anyWrite,boolean anyNotify){}
}
