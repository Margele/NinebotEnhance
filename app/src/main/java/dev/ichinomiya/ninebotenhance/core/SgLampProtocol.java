package dev.ichinomiya.ninebotenhance.core;

/**
 * Frames of the SG (JUXUN) electric lift: one FFE1 characteristic for writes and notifications, plain bytes, no checksum and no
 * session. {@code AF …} opens the session and the device answers with an AF status frame; {@code A1 <dir> 01 00 00 00 03 <pwm> 1F}
 * jogs the motor in direction 1 or 2 at a PWM of 50–100 and has to be repeated every 100 ms, otherwise the motor stops by itself
 * (a dead man's switch), and {@code A1 01 02 00 00 00 01 1F} stops it outright. The device pushes FE state frames (motor running or
 * stopped) and {@code D1 01} when the motor stalls. It has no position, so the module runs it for a set time per press. The mode
 * rotation, speed configuration, name and password commands are never sent.
 */
public final class SgLampProtocol {
    public static final String SERVICE="0000ffe0-0000-1000-8000-00805f9b34fb";
    public static final String CHAR="0000ffe1-0000-1000-8000-00805f9b34fb";
    public static final String NAME="JUXUN";
    public static final int DIR_ONE=1,DIR_TWO=2,MIN_PWM=50,MAX_PWM=100,HEARTBEAT_MS=100;
    public static final int PREFIX_STATUS=0xAF,PREFIX_STATE=0xFE,PREFIX_ALARM=0xD1,PREFIX_GONE=0xBF;
    /** Byte of an FE frame holding the motor state of the first key. */
    private static final int STATE_MOTOR=9;
    public static boolean lampName(String name){
        if(name==null||name.length()<NAME.length())return false;
        return name.toUpperCase(java.util.Locale.ROOT).contains(NAME);
    }
    public static byte[] init(){return new byte[]{(byte)0xAF,1,2,3,4,5,6,(byte)0xFF};}
    public static int clampPwm(int value){return Math.max(MIN_PWM,Math.min(MAX_PWM,value));}
    /** One heartbeat of a jog in direction 1 or 2 at the given PWM. */
    public static byte[] jog(int direction,int pwm){
        if(direction!=DIR_ONE&&direction!=DIR_TWO)throw new IllegalArgumentException("SG 方向为 1 或 2");
        if(pwm<MIN_PWM||pwm>MAX_PWM)throw new IllegalArgumentException("SG 速度为 50–100");
        return new byte[]{(byte)0xA1,(byte)direction,1,0,0,0,3,(byte)pwm,0x1F};
    }
    public static byte[] stop(){return new byte[]{(byte)0xA1,1,2,0,0,0,1,0x1F};}
    public static boolean connected(byte[] value){return value!=null&&value.length>0&&(value[0]&0xff)==PREFIX_STATUS;}
    public static boolean alarm(byte[] value){return value!=null&&value.length>=2&&(value[0]&0xff)==PREFIX_ALARM&&(value[1]&0xff)==1;}
    /** Motor state from an FE frame: 0 stopped, 1 or 2 running in that direction; -1 for any other frame. */
    public static int motor(byte[] value){
        if(value==null||value.length<=STATE_MOTOR||(value[0]&0xff)!=PREFIX_STATE)return -1;
        int low=value[STATE_MOTOR]&0x0f;
        return low==1||low==2?low:0;
    }
    private SgLampProtocol(){}
}
