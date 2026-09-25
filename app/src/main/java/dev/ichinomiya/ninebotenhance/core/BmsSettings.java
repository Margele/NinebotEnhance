package dev.ichinomiya.ninebotenhance.core;

/**
 * The bound protection board: its address, which protocol it speaks and how often it is polled while something watches the
 * link. The protocol is either one of the known boards or automatic, in which case the advertised name decides at connect.
 */
public record BmsSettings(String mac,int pollMs,int protocol){
    public static final int MIN_POLL_MS=1000,MAX_POLL_MS=10000,DEFAULT_POLL_MS=2000,POLL_STEP_MS=500;
    /** Protocol ids; 0 leaves the choice to the advertised name and the services the board exposes. */
    public static final int PROTOCOL_AUTO=0,PROTOCOL_DL=1,PROTOCOL_ANT=2,PROTOCOL_JBD=3,PROTOCOL_JK=4,PROTOCOL_YY=5;
    public static final String[] PROTOCOL_NAMES={"自动","DL","ANT","JBD","JK","彦阳"};
    public static final BmsSettings NONE=new BmsSettings("",DEFAULT_POLL_MS,PROTOCOL_AUTO);
    public BmsSettings{
        mac=LampSettings.normalizeMac(mac);
        pollMs=Math.max(MIN_POLL_MS,Math.min(MAX_POLL_MS,pollMs/POLL_STEP_MS*POLL_STEP_MS));
        if(protocol<PROTOCOL_AUTO||protocol>=PROTOCOL_NAMES.length)protocol=PROTOCOL_AUTO;
    }
    public BmsSettings(String mac,int pollMs){this(mac,pollMs,PROTOCOL_AUTO);}
    public boolean bound(){return LampSettings.validMac(mac);}
    /** Readings older than this are shown as unknown. */
    public long limitMs(){return pollMs*3L+1000;}
    public BmsSettings withMac(String value){return new BmsSettings(value,pollMs,protocol);}
    public BmsSettings withPollMs(int value){return new BmsSettings(mac,value,protocol);}
    public BmsSettings withProtocol(int value){return new BmsSettings(mac,pollMs,value);}
    public String label(){return bound()?mac+" · "+(pollMs%1000==0?pollMs/1000+"":String.format(java.util.Locale.ROOT,"%.1f",pollMs/1000f))+" 秒":"未绑定";}
    public static String protocolName(int protocol){
        return protocol>=PROTOCOL_AUTO&&protocol<PROTOCOL_NAMES.length?PROTOCOL_NAMES[protocol]:PROTOCOL_NAMES[PROTOCOL_AUTO];
    }
}
