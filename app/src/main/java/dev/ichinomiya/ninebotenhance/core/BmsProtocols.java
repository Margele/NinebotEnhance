package dev.ichinomiya.ninebotenhance.core;

import java.util.Collection;
import java.util.Locale;

/** Which protection board a scanned device looks like, by advertised name or exposed services; only used to mark scan results, the bound protocol is chosen by hand. */
public final class BmsProtocols {
    /** The id for an advertised name, or UNKNOWN when the name belongs to none of the boards. */
    public static int name(String label){
        if(DlBmsProtocol.deviceName(label))return BmsSettings.PROTOCOL_DL;
        if(AntBmsProtocol.name(label))return BmsSettings.PROTOCOL_ANT;
        if(JbdBmsProtocol.name(label))return BmsSettings.PROTOCOL_JBD;
        if(JkBmsProtocol.name(label))return BmsSettings.PROTOCOL_JK;
        if(YyBmsProtocol.name(label))return BmsSettings.PROTOCOL_YY;
        return BmsSettings.PROTOCOL_UNKNOWN;
    }
    /** The id for the services a device exposes; UNKNOWN when they give nothing away. */
    public static int services(Collection<String> uuids){
        if(uuids==null||uuids.isEmpty())return BmsSettings.PROTOCOL_UNKNOWN;
        if(offers(uuids,BmsProtocol.FF00))return BmsSettings.PROTOCOL_JBD;
        if(offers(uuids,BmsProtocol.NUS_SERVICE))return BmsSettings.PROTOCOL_YY;
        if(offers(uuids,BmsProtocol.FFE0)&&offers(uuids,"0000ffe2-0000-1000-8000-00805f9b34fb"))return BmsSettings.PROTOCOL_DL;
        return BmsSettings.PROTOCOL_UNKNOWN;
    }
    private static boolean offers(Collection<String> uuids,String uuid){
        return uuids.contains(uuid.toLowerCase(Locale.ROOT));
    }
    /** A board the module frames itself; the DL board keeps its own encrypted path in BmsController. */
    public static BmsProtocol create(int id){
        switch(id){
            case BmsSettings.PROTOCOL_ANT:return new AntBmsProtocol();
            case BmsSettings.PROTOCOL_JBD:return new JbdBmsProtocol();
            case BmsSettings.PROTOCOL_JK:return new JkBmsProtocol();
            case BmsSettings.PROTOCOL_YY:return new YyBmsProtocol();
            default:return null;
        }
    }
    private BmsProtocols(){}
}
