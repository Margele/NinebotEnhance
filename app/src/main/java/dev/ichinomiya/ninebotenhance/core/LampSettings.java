package dev.ichinomiya.ninebotenhance.core;

import java.util.Locale;

/**
 * Binding of one lamp controller: which kind it is ({@link LampKind}), its BLE address, the password the device authenticates
 * with where it has one, the travel speed and how many notches the usable range is split into, whether the light direction is
 * flipped, and for a timed kind how long one press runs the motor. {@code speed} is the small controller's 1–20 step scale; the
 * TX protocol carries it times five, the SG lift as a PWM of 50–100.
 * <p>
 * A positional device reports (or is assigned) travel limits, and the range between them is split into {@code steps} equal
 * notches: one volume press moves one notch and the top of travel reads as 100 %, whatever raw height that turns out to be.
 * {@code reversed} swaps which end is bright — done entirely in the module by mirroring the target or the jog direction, so no
 * device is ever sent its own direction setting.
 */
public record LampSettings(String mac,String password,int speed,int steps,boolean reversed,boolean volumeControl,LampKind kind,int jogMs){
    public static final int MIN_SPEED=1,MAX_SPEED=20,DEFAULT_SPEED=10;
    public static final int MIN_STEPS=5,MAX_STEPS=15,DEFAULT_STEPS=8;
    /** How long one press runs a timed lift, in milliseconds; the screen slider moves in tenths of a second. */
    public static final int MIN_JOG_MS=200,MAX_JOG_MS=5000,DEFAULT_JOG_MS=1000,JOG_STEP_MS=100;
    /** The TX hoist never targets the reported upper limit itself: one raw unit below it is the usable top (0–73 reaches 72). */
    public static final int TOP_MARGIN=1;
    /** TX travel top: the reported limit less the margin. Other kinds use {@link #top(int,int)}. */
    public static int topLimit(int low,int high){return topLimit(low,high,TOP_MARGIN);}
    private static int topLimit(int low,int high,int margin){int lo=Math.min(low,high),hi=Math.max(low,high);return hi-lo>=2?hi-margin:hi;}
    public static final LampSettings NONE=new LampSettings("","",DEFAULT_SPEED,DEFAULT_STEPS,false,true);
    /** A TX binding; the shape every earlier save had. */
    public LampSettings(String mac,String password,int speed,int steps,boolean reversed,boolean volumeControl){
        this(mac,password,speed,steps,reversed,volumeControl,LampKind.TX,DEFAULT_JOG_MS);
    }
    public LampSettings{
        mac=normalizeMac(mac);
        password=password==null?"":password.trim();
        speed=Math.max(MIN_SPEED,Math.min(MAX_SPEED,speed));
        steps=Math.max(MIN_STEPS,Math.min(MAX_STEPS,steps));
        kind=kind==null?LampKind.TX:kind;
        jogMs=Math.max(MIN_JOG_MS,Math.min(MAX_JOG_MS,jogMs));
    }
    /** An address, plus a password where the device wants one: a keyed device ignores every command until it is accepted. */
    public boolean bound(){return !mac.isEmpty()&&kind.validPassword(password);}
    /** TX protocol speed 0–100 from the 1–20 step scale, like the vendor application's slider. */
    public int protocolSpeed(){return Math.max(TxLampProtocol.MIN_SPEED,Math.min(TxLampProtocol.MAX_SPEED,speed*5));}
    /** SG motor PWM 50–100 from the same scale. */
    public int pwm(){return SgLampProtocol.clampPwm(speed*5);}
    /** Usable top of the travel for this kind of device. */
    public int top(int low,int high){return topLimit(low,high,kind.topMargin);}
    /** Raw device units moved by one volume press: the travel range split into the step count, at least one unit. */
    public int stepUnits(int low,int high){return Math.max(1,Math.round(Math.max(0,top(low,high)-Math.min(low,high))/(float)steps));}
    /** Displayed brightness 0–100 for a raw position between the travel limits, flipped when reversed; -1 when unknown. */
    public int displayPercent(int position,int low,int high){
        if(position<0)return -1;
        int lo=Math.min(low,high),hi=top(low,high);
        if(hi<=lo)return reversed?100:0;
        int normal=Math.max(0,Math.min(100,Math.round((Math.max(lo,Math.min(hi,position))-lo)*100f/(hi-lo))));
        return reversed?100-normal:normal;
    }
    /** Displayed size of one notch, e.g. 20 for five notches; UI only. */
    public int stepPercent(){return Math.max(1,Math.round(100f/steps));}
    public LampSettings withMac(String value){return new LampSettings(value,password,speed,steps,reversed,volumeControl,kind,jogMs);}
    public LampSettings withPassword(String value){return new LampSettings(mac,value,speed,steps,reversed,volumeControl,kind,jogMs);}
    public LampSettings withKind(LampKind value){return new LampSettings(mac,password,speed,steps,reversed,volumeControl,value,jogMs);}
    public String label(){return bound()?kind.label+" · "+mac+(kind.positional?" · "+steps+" 档":" · "+jogMs/100/10f+" 秒"):"未绑定";}
    /** Upper case colon form, or an empty string when the text is not a BLE address. */
    public static String normalizeMac(String value){
        if(value==null)return "";
        String text=value.trim().toUpperCase(Locale.ROOT).replace('-',':');
        return validMac(text)?text:"";
    }
    public static boolean validMac(String value){
        if(value==null||value.length()!=17)return false;
        for(int i=0;i<17;i++){
            char c=value.charAt(i);
            if(i%3==2){if(c!=':')return false;continue;}
            if((c<'0'||c>'9')&&(c<'A'||c>'F'))return false;
        }
        return true;
    }
    /** Advertising name of a bound device: MOTORE plus the last three address bytes, as the vendor firmware builds it. */
    public static String advertisedName(String mac){
        String normalized=normalizeMac(mac);
        return normalized.isEmpty()?"":TxLampProtocol.NAME_PREFIX+normalized.substring(9).replace(":","");
    }
}
