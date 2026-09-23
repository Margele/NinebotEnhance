package dev.ichinomiya.ninebotenhance.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The input devices the shell can see, read from the listing of {@code getevent -pi}: the device node, bus, identity and the raw
 * ranges of its touch axes. Only USB and Bluetooth devices are ever bound; the phone's own panels are never touched.
 */
public final class TouchPanelProbe {
    public static final int BUS_USB=0x03,BUS_BLUETOOTH=0x05;
    public static final int ABS_X=0x00,ABS_Y=0x01,ABS_MT_SLOT=0x2f,ABS_MT_POSITION_X=0x35,ABS_MT_POSITION_Y=0x36;
    public record Axis(int min,int max){
        public Axis{if(max<=min)throw new IllegalArgumentException("axis "+min+".."+max);}
        public float normalize(int value){return Math.max(0f,Math.min(1f,(value-min)/(float)(max-min)));}
    }
    /** {@code slots} is 0 for a single-touch device (ABS_X / ABS_Y with BTN_TOUCH), otherwise the number of protocol B slots. */
    public record Device(String path,int bus,int vendor,int product,String name,Axis x,Axis y,int slots,boolean direct){
        public boolean touchscreen(){return x!=null&&y!=null;}
        public boolean external(){return bus==BUS_USB||bus==BUS_BLUETOOTH;}
        public boolean matches(TouchPanel panel){return panel.matches(vendor,product,name);}
        public String describe(){
            return path+" "+name+" "+String.format(Locale.ROOT,"%04x:%04x",vendor,product)+" bus="+bus
                    +(touchscreen()?" x="+x.min()+".."+x.max()+" y="+y.min()+".."+y.max()+" slots="+slots:" no-touch")+(direct?" direct":"");
        }
    }
    private static final Pattern DEVICE=Pattern.compile("^add device \\d+: (\\S+)\\s*$");
    private static final Pattern FIELD=Pattern.compile("^\\s+(bus|vendor|product|name):?\\s+(.*?)\\s*$");
    private static final Pattern ABS=Pattern.compile("^\\s*(?:ABS \\(0003\\):\\s*)?([0-9a-fA-F]{4})\\s*: value -?\\d+, min (-?\\d+), max (-?\\d+)");
    public static List<Device> parse(String text){
        List<Device> out=new ArrayList<>();Builder current=null;
        for(String line:(text==null?"":text).split("\n")){
            Matcher device=DEVICE.matcher(line);
            if(device.find()){if(current!=null)out.add(current.build());current=new Builder(device.group(1));continue;}
            if(current==null)continue;
            Matcher axis=ABS.matcher(line);
            if(axis.find()){current.axis(Integer.parseInt(axis.group(1),16),Integer.parseInt(axis.group(2)),Integer.parseInt(axis.group(3)));continue;}
            Matcher field=FIELD.matcher(line);
            if(field.find())current.field(field.group(1),field.group(2));
            else if(line.trim().equals("INPUT_PROP_DIRECT"))current.direct=true;
        }
        if(current!=null)out.add(current.build());
        return out;
    }
    /** The first external touch device with this identity, or null. */
    public static Device find(List<Device> devices,TouchPanel panel){
        for(Device device:devices)if(device.external()&&device.touchscreen()&&device.matches(panel))return device;
        return null;
    }
    private static final class Builder{
        final String path;int bus,vendor,product;String name="";boolean direct;final Map<Integer,Axis> axes=new HashMap<>();
        Builder(String path){this.path=path;}
        void field(String key,String value){
            switch(key){
                case "bus":bus=hex(value);break;
                case "vendor":vendor=hex(value);break;
                case "product":product=hex(value);break;
                default:name=value.length()>=2&&value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value;
            }
        }
        void axis(int code,int min,int max){try{axes.put(code,new Axis(min,max));}catch(IllegalArgumentException ignored){}}
        Device build(){
            boolean multitouch=axes.containsKey(ABS_MT_SLOT)&&axes.containsKey(ABS_MT_POSITION_X)&&axes.containsKey(ABS_MT_POSITION_Y);
            Axis x=multitouch?axes.get(ABS_MT_POSITION_X):axes.get(ABS_X),y=multitouch?axes.get(ABS_MT_POSITION_Y):axes.get(ABS_Y);
            int slots=multitouch?axes.get(ABS_MT_SLOT).max()+1:0;
            return new Device(path,bus,vendor,product,name,x,y,slots,direct);
        }
        private static int hex(String value){try{return Integer.parseInt(value.trim(),16)&0xffff;}catch(NumberFormatException e){return 0;}}
    }
    private TouchPanelProbe(){}
}
