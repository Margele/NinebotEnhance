package dev.ichinomiya.ninebotenhance.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** In-process read-only pack voltage and dashboard charge level, isolated by vehicle. Times describe receipt, not sampling. */
public final class BatteryTelemetry {
    public enum Source { BLUETOOTH, SERVER }
    public record Value(float number,long wallTime,long elapsedTime,Source source) {}
    public record Snapshot(Value voltage,Value percent) {}
    /**
     * Verified 6.10.10 command names for the M5P (model 14103) configuration. Lithium packs sit in up to three bays
     * (bms1/bms2/bms3, register 26 each: rVoltage, rVoltage2, rVoltage3); the Ninebot battery page first reads the
     * bay flags in rBool (bits 256/512/1024) and then the matching register. The display board also reports the
     * measured bus voltage in rVrlaVoltage (dis/177). On the tested lithium M5P the bay register returned the
     * constant nominal 72.00 V while the display register followed the real pack (79.31 V at 87 percent), so the
     * display register is preferred whenever it answers. Every register carries one 10 mV value.
     */
    public static final String VOLTAGE_COMMAND="rVoltage",VOLTAGE2_COMMAND="rVoltage2",VOLTAGE3_COMMAND="rVoltage3",VRLA_VOLTAGE_COMMAND="rVrlaVoltage";
    public static final String[] READ_COMMANDS={VOLTAGE_COMMAND,VOLTAGE2_COMMAND,VOLTAGE3_COMMAND,VRLA_VOLTAGE_COMMAND};
    public static final String BAY_FLAGS_COMMAND="rBool",DASH_LEVEL_COMMAND="rBattery";
    /** BMS state-of-charge registers per bay (bms1-3/50); read once per session only to cross-check the byte order. */
    public static final String[] SOC_COMMANDS={"rBms1SOC","rBms2SOC","rBms3SOC"};
    public static String describe(byte[] data){
        if(data==null||data.length<2)return "short";
        return "LE="+u16(data,0)+" BE="+(((data[0]&0xff)<<8)|(data[1]&0xff));
    }
    /** Diagnostic probes: the SOC registers of the occupied bays. */
    public static List<String> probeCandidates(int bays){
        List<String> names=new ArrayList<>();
        if(bays>0){if((bays&BAY1)!=0)names.add(SOC_COMMANDS[0]);if((bays&BAY2)!=0)names.add(SOC_COMMANDS[1]);if((bays&BAY3)!=0)names.add(SOC_COMMANDS[2]);}
        return names;
    }
    /** Bay voltage registers worth reading: the occupied bays in order, or all three while the bays are unknown or empty. */
    public static List<String> bayCommands(int bays){
        List<String> names=new ArrayList<>();
        if(bays>0){if((bays&BAY1)!=0)names.add(VOLTAGE_COMMAND);if((bays&BAY2)!=0)names.add(VOLTAGE2_COMMAND);if((bays&BAY3)!=0)names.add(VOLTAGE3_COMMAND);}
        else{names.add(VOLTAGE_COMMAND);names.add(VOLTAGE2_COMMAND);names.add(VOLTAGE3_COMMAND);}
        return names;
    }
    public static final int BAY1=1,BAY2=2,BAY3=4;
    /** Lithium bay presence from the rBool register: bit 256 = bay 1, 512 = bay 2, 1024 = bay 3; -1 for a short payload. */
    public static int lithiumBays(byte[] data){
        if(data==null||data.length<2)return -1;
        int flags=u16(data,0);
        return ((flags&256)!=0?BAY1:0)|((flags&512)!=0?BAY2:0)|((flags&1024)!=0?BAY3:0);
    }
    /** Registers worth reading: the display register first, then the occupied bays in order, or every bay while the bays are unknown or empty. */
    public static List<String> voltageCandidates(int bays){
        List<String> names=new ArrayList<>();names.add(VRLA_VOLTAGE_COMMAND);
        if(bays>0){if((bays&BAY1)!=0)names.add(VOLTAGE_COMMAND);if((bays&BAY2)!=0)names.add(VOLTAGE2_COMMAND);if((bays&BAY3)!=0)names.add(VOLTAGE3_COMMAND);}
        else{names.add(VOLTAGE_COMMAND);names.add(VOLTAGE2_COMMAND);names.add(VOLTAGE3_COMMAND);}
        return names;
    }
    public static boolean displayRegister(String tag){return VRLA_VOLTAGE_COMMAND.equals(tag);}
    public static final Snapshot EMPTY=new Snapshot(null,null);
    private final LinkedHashMap<String,Snapshot> vehicles=new LinkedHashMap<>();
    private String selected="",pinned="";private boolean session;

    public synchronized void select(String key){selected=key==null?"":key;}
    public synchronized String selectedKey(){return session?pinned:selected;}
    public synchronized void beginSession(){pinned=selected;session=true;}
    public synchronized void endSession(){session=false;pinned="";}
    public synchronized Snapshot snapshot(){return vehicles.getOrDefault(session?pinned:selected,EMPTY);}
    public synchronized boolean update(String key,Float voltage,Source source,long wall,long elapsed){
        if(key==null||key.isEmpty()||voltage==null||!Float.isFinite(voltage)||source==null||wall<=0||elapsed<0)return false;
        Snapshot previous=vehicles.getOrDefault(key,EMPTY);
        Snapshot next=new Snapshot(merge(previous.voltage(),voltage,source,wall,elapsed),previous.percent());
        if(next.equals(previous))return false;
        vehicles.put(key,next);
        trim(key);
        return true;
    }
    /** The dashboard charge level; 0-100 only, anything else is refused. */
    public synchronized boolean updatePercent(String key,Integer percent,Source source,long wall,long elapsed){
        if(key==null||key.isEmpty()||percent==null||percent<0||percent>100||source==null||wall<=0||elapsed<0)return false;
        Snapshot previous=vehicles.getOrDefault(key,EMPTY);
        Snapshot next=new Snapshot(previous.voltage(),merge(previous.percent(),percent.floatValue(),source,wall,elapsed));
        if(next.equals(previous))return false;
        vehicles.put(key,next);
        trim(key);
        return true;
    }
    private void trim(String key){
        if(vehicles.size()>8){for(String candidate:vehicles.keySet().toArray(new String[0]))if(!candidate.equals(selected)&&!candidate.equals(pinned)&&!candidate.equals(key)){vehicles.remove(candidate);break;}}
    }
    /** The dashboard level register (dis/181) carries one little-endian whole percent. */
    public static Integer decodeLevel(String tag,byte[] data){
        if(!levelRecognized(tag)||data==null||data.length<2)return null;
        int percent=u16(data,0);
        return percent<=100?percent:null;
    }
    public static boolean levelRecognized(String tag){return DASH_LEVEL_COMMAND.equals(tag);}
    /** The charge level as text; "--" while unknown or stale. */
    public static String level(Value value){return value==null?"--":Math.round(value.number())+"%";}
    private static Value merge(Value old,float number,Source source,long wall,long elapsed){
        if(old!=null){
            if(elapsed<old.elapsedTime())return old;
            // Repeated server/cache values cannot make an old measurement look newly reported.
            if(source==Source.SERVER&&(Float.compare(number,old.number())==0||old.source()==Source.BLUETOOTH&&elapsed-old.elapsedTime()<30000))return old;
        }
        return new Value(number,wall,elapsed,source);
    }
    /** Either voltage register carries 10 mV units in its first little-endian register; implausible values yield nothing. */
    public static Float decode(String tag,byte[] data){
        if(!recognized(tag)||data==null||data.length<2)return null;
        float volts=u16(data,0)*.01f;
        return volts>=10&&volts<=200?volts:null;
    }
    public static boolean recognized(String tag){for(String name:READ_COMMANDS)if(name.equals(tag))return true;return false;}
    public static int u16(byte[] data,int offset){return (data[offset]&0xff)|((data[offset+1]&0xff)<<8);}
    public static String voltage(Value value){return value==null?"--":String.format(Locale.ROOT,"%.1f",value.number());}
    public static boolean stale(Value value,long now){return value!=null&&now-value.elapsedTime()>=120000;}
    public synchronized String summary(long now){Value v=snapshot().voltage();return "voltage="+(v==null?"missing":v.source()+":"+Math.max(0,now-v.elapsedTime())+"ms");}
}
