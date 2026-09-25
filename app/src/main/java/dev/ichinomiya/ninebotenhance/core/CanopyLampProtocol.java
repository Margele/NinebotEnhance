package dev.ichinomiya.ninebotenhance.core;

/**
 * Frames of the 摩灯客 canopy / lift controller: {@code B3}, the total length, a command, its data and a checksum that is the low
 * byte of the sum of everything before it, plus a few bare commands without that envelope. The module uses two of them: the bare
 * {@code CC <position>}, which drives to one of the thirty absolute positions 0–29, and 0x1D, the configuration query, whose 0x21
 * answer carries the current position in byte 14; the device also pushes 0x20 status frames with the position in byte 3, so the
 * height shown is always the device's own. The ten digit password the vendor application asks for is read from the device and
 * compared by the application alone; the device enforces nothing, so the module neither reads nor sends it. Nothing else is written:
 * no calibration, no travel range, no run mode.
 */
public final class CanopyLampProtocol {
    public static final String SERVICE="0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String CHAR="0000fff2-0000-1000-8000-00805f9b34fb";
    public static final int HEADER=0xB3,CMD_QUERY_CONFIG=0x1D,RESP_STATUS=0x20,RESP_CONFIG=0x21,BARE_MOVE=0xCC;
    public static final int MIN_POSITION=0,MAX_POSITION=29;
    /** Byte carrying the position in a status frame and in a configuration answer. */
    private static final int STATUS_POSITION=3,CONFIG_POSITION=14;
    public static int checksum(byte[] data,int length){int total=0;for(int i=0;i<length;i++)total+=data[i]&0xff;return total&0xff;}
    public static byte[] frame(int cmd,byte... data){
        byte[] body=data==null?new byte[0]:data;
        if(cmd<0||cmd>0xff||body.length>0xf0)throw new IllegalArgumentException("卷帘帧超出范围");
        byte[] out=new byte[4+body.length];
        out[0]=(byte)HEADER;out[1]=(byte)out.length;out[2]=(byte)cmd;System.arraycopy(body,0,out,3,body.length);
        out[out.length-1]=(byte)checksum(out,out.length-1);
        return out;
    }
    /** 0x1D: ask for the configuration; the 0x21 answer includes the position. */
    public static byte[] queryConfig(){return frame(CMD_QUERY_CONFIG,(byte)0);}
    /** The bare CC command: go to an absolute position. */
    public static byte[] moveTo(int position){
        if(position<MIN_POSITION||position>MAX_POSITION)throw new IllegalArgumentException("卷帘位置为 0–29");
        return new byte[]{(byte)BARE_MOVE,(byte)position};
    }
    public static int clampPosition(int value){return Math.max(MIN_POSITION,Math.min(MAX_POSITION,value));}
    /**
     * Position carried by a status (0x20) or configuration (0x21) frame, -1 for anything else. The header and the declared length
     * are checked; the checksum is not, matching the vendor application, which indexes the bytes directly.
     */
    public static int position(byte[] value){
        if(value==null||value.length<4||(value[0]&0xff)!=HEADER)return -1;
        int length=value[1]&0xff;
        if(length<4||length>value.length)return -1;
        int cmd=value[2]&0xff,at=cmd==RESP_STATUS?STATUS_POSITION:cmd==RESP_CONFIG?CONFIG_POSITION:-1;
        if(at<0||at>=length-1)return -1;
        int position=value[at]&0xff;
        return position>MAX_POSITION?-1:position;
    }
    private CanopyLampProtocol(){}
}
