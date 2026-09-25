package dev.ichinomiya.ninebotenhance.core;

/**
 * The bound protection board: its address, the protocol it speaks, how often it is polled while something watches the link,
 * and whether it replaces the dashboard as the source of the voltage and power the host shows.
 */
public record BmsSettings(String mac,int pollMs,int protocol,boolean preferBms){
    public static final int MIN_POLL_MS=1000,MAX_POLL_MS=10000,DEFAULT_POLL_MS=2000,POLL_STEP_MS=500;
    /** Protocol ids; 0 is only what a scan reports for a board it does not recognise, a bound board always has one of the five. */
    public static final int PROTOCOL_UNKNOWN=0,PROTOCOL_DL=1,PROTOCOL_ANT=2,PROTOCOL_JBD=3,PROTOCOL_JK=4,PROTOCOL_YY=5;
    /** Names of the five protocols, indexed by {@code protocol - PROTOCOL_DL}. */
    public static final String[] PROTOCOL_NAMES={"DL","ANT","JBD","JK","彦阳"};
    public static final BmsSettings NONE=new BmsSettings("",DEFAULT_POLL_MS,PROTOCOL_DL,false);
    public BmsSettings{
        mac=LampSettings.normalizeMac(mac);
        pollMs=Math.max(MIN_POLL_MS,Math.min(MAX_POLL_MS,pollMs/POLL_STEP_MS*POLL_STEP_MS));
        if(protocol<PROTOCOL_DL||protocol>PROTOCOL_YY)protocol=PROTOCOL_DL;
    }
    public BmsSettings(String mac,int pollMs){this(mac,pollMs,PROTOCOL_DL,false);}
    public BmsSettings(String mac,int pollMs,int protocol){this(mac,pollMs,protocol,false);}
    public boolean bound(){return LampSettings.validMac(mac);}
    /** Readings older than this are shown as unknown. */
    public long limitMs(){return pollMs*3L+1000;}
    public BmsSettings withMac(String value){return new BmsSettings(value,pollMs,protocol,preferBms);}
    public BmsSettings withPollMs(int value){return new BmsSettings(mac,value,protocol,preferBms);}
    public BmsSettings withProtocol(int value){return new BmsSettings(mac,pollMs,value,preferBms);}
    public BmsSettings withPreferBms(boolean value){return new BmsSettings(mac,pollMs,protocol,value);}
    public String label(){return bound()?mac+" · "+protocolName(protocol)+" · "+(pollMs%1000==0?pollMs/1000+"":String.format(java.util.Locale.ROOT,"%.1f",pollMs/1000f))+" 秒":"未绑定";}
    public static String protocolName(int protocol){
        return protocol>=PROTOCOL_DL&&protocol<=PROTOCOL_YY?PROTOCOL_NAMES[protocol-PROTOCOL_DL]:PROTOCOL_NAMES[0];
    }
}
