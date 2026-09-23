package dev.ichinomiya.ninebotenhance.core;

import java.util.Locale;

/**
 * The external touch panel bound to the virtual display: its identity in the input subsystem (USB / Bluetooth vendor, product
 * and name), how it is mounted relative to the picture in quarter turns clockwise (used until a calibration exists), whether its
 * contacts are painted on the frame, and the calibration solved from taps on the frame's targets.
 */
public record TouchPanel(int vendor,int product,String name,int rotation,boolean marks,TouchCalibration calibration){
    public static final TouchPanel NONE=new TouchPanel(0,0,"",0,true,null);
    public static final int ROTATIONS=4;
    @FunctionalInterface public interface IntSetting{int get(String key,int fallback);}
    @FunctionalInterface public interface StringSetting{String get(String key,String fallback);}
    @FunctionalInterface public interface IntWriter{void put(String key,int value);}
    @FunctionalInterface public interface StringWriter{void put(String key,String value);}
    public TouchPanel{
        name=name==null?"":name.trim();
        if(rotation<0||rotation>=ROTATIONS)throw new IllegalArgumentException("rotation "+rotation);
        vendor&=0xffff;product&=0xffff;
    }
    public boolean bound(){return !name.isEmpty();}
    public boolean calibrated(){return calibration!=null;}
    public String id(){return String.format(Locale.ROOT,"%04x:%04x",vendor,product);}
    public String label(){return bound()?name+" "+id():"未绑定";}
    public boolean matches(int vendor,int product,String name){
        return bound()&&this.vendor==(vendor&0xffff)&&this.product==(product&0xffff)&&this.name.equals(name==null?"":name.trim());
    }
    public boolean sameDevice(TouchPanel other){return other!=null&&matches(other.vendor,other.product,other.name);}
    public TouchPanel withRotation(int value){return new TouchPanel(vendor,product,name,value,marks,calibration);}
    public TouchPanel withMarks(boolean value){return new TouchPanel(vendor,product,name,rotation,value,calibration);}
    public TouchPanel withCalibration(TouchCalibration value){return new TouchPanel(vendor,product,name,rotation,marks,value);}
    public static TouchPanel read(IntSetting ints,StringSetting strings){
        try{
            return new TouchPanel(ints.get("touch_vendor",0),ints.get("touch_product",0),strings.get("touch_name",""),ints.get("touch_rotation",0),
                    ints.get("touch_marks",1)!=0,TouchCalibration.decode(strings.get("touch_calibration","")));
        }catch(IllegalArgumentException e){return NONE;}
    }
    public void write(IntWriter ints,StringWriter strings){
        ints.put("touch_vendor",vendor);ints.put("touch_product",product);strings.put("touch_name",name);ints.put("touch_rotation",rotation);
        ints.put("touch_marks",marks?1:0);strings.put("touch_calibration",calibration==null?"":calibration.encode());
    }
}
