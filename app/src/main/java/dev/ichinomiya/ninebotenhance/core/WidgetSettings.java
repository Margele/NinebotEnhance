package dev.ichinomiya.ninebotenhance.core;

import java.util.*;

/** Render switches, per-widget options, read intervals, chart windows, hill-hold thresholds, the column order and display conditions; notification authorization and allowlist remain separate. */
public record WidgetSettings(int mask,int tyreIntervalSeconds,int voltageIntervalMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax,
                             int speedIntervalMs,int powerIntervalMs,int holdPowerMax,int holdSeconds,int speedChartSeconds,int powerChartSeconds,
                             List<Integer> order,Map<Integer,WidgetCondition> conditions) {
    public static final int PHONE=1,TYRES=2,MUSIC=4,NOTIFICATIONS=16,TYRE_FRONT=32,TYRE_REAR=64,VOLTAGE=128,TYRE_READ=256,
            VOLTAGE_READ=512,VOLTAGE_CHART=1024,MUSIC_AUTO_HIDE=2048,VOLUME=4096,REGISTER_PROBE=8192,HILL_HOLD_DODGE=16384,
            SPEED=32768,POWER=65536,SPEED_CHART=131072,POWER_CHART=262144,LAMP=524288,BMS=1048576,VOLTAGE_FROM_BMS=2097152,POWER_FROM_BMS=4194304;
    public static final int ALL=PHONE|TYRES|MUSIC|NOTIFICATIONS|TYRE_FRONT|TYRE_REAR|VOLTAGE|TYRE_READ|VOLTAGE_READ|VOLTAGE_CHART|MUSIC_AUTO_HIDE|VOLUME|REGISTER_PROBE|HILL_HOLD_DODGE
            |SPEED|POWER|SPEED_CHART|POWER_CHART|LAMP|BMS|VOLTAGE_FROM_BMS|POWER_FROM_BMS;
    /** Switches introduced by later preference versions; masks saved by older builds get them switched on once. */
    public static final int ADDED_IN_V2=VOLTAGE_READ|VOLTAGE_CHART|MUSIC_AUTO_HIDE|VOLUME,ADDED_IN_V3=HILL_HOLD_DODGE,ADDED_IN_V4=SPEED_CHART|POWER_CHART;
    /**
     * Version 5 changed the hill-hold minimum time semantics (it now gates the release too) and reset its default; older saves
     * take the new default. Version 6 added the lamp card, which older saves must not inherit: without a bound lamp it would
     * only ever read "未连接". Version 7 added the BMS card and the two BMS-first source switches, likewise not inherited.
     */
    public static final int PREFERENCE_VERSION=7;
    /** Switches that are off in a fresh install: the diagnostic probe, the two optional ride cards and the lamp. */
    public static final int OFF_BY_DEFAULT=REGISTER_PROBE|SPEED|POWER|LAMP|BMS|VOLTAGE_FROM_BMS|POWER_FROM_BMS;
    /** Marker inside the order: entries before it form the right column (bottom up), entries after it the left column (bottom up). */
    public static final int COLUMN_DIVIDER=0;
    /** Cards of the two columns; the notification block is one of them and always belongs to the right column. */
    public static final List<Integer> CARDS=List.of(NOTIFICATIONS,PHONE,MUSIC,TYRES,VOLTAGE,SPEED,POWER,LAMP,BMS);
    /** Default: everything in the right column, bottom up, and an empty left column. */
    public static final List<Integer> DEFAULT_ORDER=List.of(NOTIFICATIONS,PHONE,MUSIC,TYRES,VOLTAGE,SPEED,POWER,LAMP,BMS,COLUMN_DIVIDER);
    /** Widgets that accept a display condition, in the index order the renderer uses for its timers. */
    public static final int[] CONDITIONAL={PHONE,MUSIC,TYRES,VOLTAGE,SPEED,POWER,NOTIFICATIONS,VOLUME,LAMP,BMS};
    public static final int MIN_TYRE_SECONDS=5,MAX_TYRE_SECONDS=60,DEFAULT_TYRE_SECONDS=30;
    /** Voltage, speed and power are read at sub-second intervals; sliders move in READ_STEP_MS steps. */
    public static final int MIN_READ_MS=500,MAX_READ_MS=15000,DEFAULT_READ_MS=1000,READ_STEP_MS=500;
    public static final int MIN_MUSIC_HIDE_SECONDS=3,MAX_MUSIC_HIDE_SECONDS=15,DEFAULT_MUSIC_HIDE_SECONDS=5;
    /** Voltage, speed and power charts each keep their own window within the same range. */
    public static final int MIN_CHART_SECONDS=10,MAX_CHART_SECONDS=60,DEFAULT_CHART_SECONDS=30;
    /** Hill hold: motor power within (holdPowerMin, holdPowerMax] (raw rPower units, about watts) while speed is at most holdSpeedMax km/h, for at least holdSeconds; leaving that state for holdSeconds ends it. */
    public static final int MIN_HOLD_POWER=50,MAX_HOLD_POWER=200,DEFAULT_HOLD_POWER=100,MIN_HOLD_SPEED=0,MAX_HOLD_SPEED=3,DEFAULT_HOLD_SPEED=0;
    public static final int MIN_HOLD_POWER_MAX=200,MAX_HOLD_POWER_MAX=500,DEFAULT_HOLD_POWER_MAX=300,MIN_HOLD_SECONDS=1,MAX_HOLD_SECONDS=5,DEFAULT_HOLD_SECONDS=1;
    /** Readings older than these multiples of their read interval are shown as unknown instead of as stale numbers. */
    public static final int TYRE_EXPIRY_FACTOR=2,VOLTAGE_EXPIRY_FACTOR=5;
    public static final WidgetSettings DEFAULT=new WidgetSettings(ALL&~OFF_BY_DEFAULT,DEFAULT_TYRE_SECONDS,DEFAULT_READ_MS,DEFAULT_MUSIC_HIDE_SECONDS,DEFAULT_CHART_SECONDS,
            DEFAULT_HOLD_POWER,DEFAULT_HOLD_SPEED,DEFAULT_READ_MS,DEFAULT_READ_MS,DEFAULT_HOLD_POWER_MAX,DEFAULT_HOLD_SECONDS,DEFAULT_CHART_SECONDS,DEFAULT_CHART_SECONDS,DEFAULT_ORDER,Map.of());
    public WidgetSettings{
        mask&=ALL;
        tyreIntervalSeconds=clamp(tyreIntervalSeconds,MIN_TYRE_SECONDS,MAX_TYRE_SECONDS);
        voltageIntervalMs=clamp(voltageIntervalMs,MIN_READ_MS,MAX_READ_MS);
        musicHideSeconds=clamp(musicHideSeconds,MIN_MUSIC_HIDE_SECONDS,MAX_MUSIC_HIDE_SECONDS);
        chartSeconds=clamp(chartSeconds,MIN_CHART_SECONDS,MAX_CHART_SECONDS);
        holdPowerMin=clamp(holdPowerMin,MIN_HOLD_POWER,MAX_HOLD_POWER);
        holdSpeedMax=clamp(holdSpeedMax,MIN_HOLD_SPEED,MAX_HOLD_SPEED);
        speedIntervalMs=clamp(speedIntervalMs,MIN_READ_MS,MAX_READ_MS);
        powerIntervalMs=clamp(powerIntervalMs,MIN_READ_MS,MAX_READ_MS);
        holdPowerMax=clamp(holdPowerMax,MIN_HOLD_POWER_MAX,MAX_HOLD_POWER_MAX);
        holdSeconds=clamp(holdSeconds,MIN_HOLD_SECONDS,MAX_HOLD_SECONDS);
        speedChartSeconds=clamp(speedChartSeconds,MIN_CHART_SECONDS,MAX_CHART_SECONDS);
        powerChartSeconds=clamp(powerChartSeconds,MIN_CHART_SECONDS,MAX_CHART_SECONDS);
        order=normalizeOrder(order);
        conditions=normalizeConditions(conditions);
    }
    public WidgetSettings(int mask){this(mask,DEFAULT_TYRE_SECONDS,DEFAULT_READ_MS,DEFAULT_MUSIC_HIDE_SECONDS,DEFAULT_CHART_SECONDS);}
    public WidgetSettings(int mask,int tyreIntervalSeconds,int voltageIntervalMs,int musicHideSeconds,int chartSeconds){this(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,DEFAULT_HOLD_POWER,DEFAULT_HOLD_SPEED);}
    public WidgetSettings(int mask,int tyreIntervalSeconds,int voltageIntervalMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax){
        this(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,DEFAULT_READ_MS,DEFAULT_READ_MS,DEFAULT_HOLD_POWER_MAX,DEFAULT_HOLD_SECONDS);
    }
    public WidgetSettings(int mask,int tyreIntervalSeconds,int voltageIntervalMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax,
                          int speedIntervalMs,int powerIntervalMs,int holdPowerMax,int holdSeconds){
        this(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,DEFAULT_CHART_SECONDS,DEFAULT_CHART_SECONDS);
    }
    public WidgetSettings(int mask,int tyreIntervalSeconds,int voltageIntervalMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax,
                          int speedIntervalMs,int powerIntervalMs,int holdPowerMax,int holdSeconds,int speedChartSeconds,int powerChartSeconds){
        this(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,DEFAULT_ORDER,Map.of());
    }
    /** Saved preferences: masks from before the current version gain the switches that did not exist when they were saved. */
    public static WidgetSettings migrate(int version,int mask,int tyreSeconds,int voltageMs,int musicHideSeconds,int chartSeconds){
        return migrate(version,mask,tyreSeconds,voltageMs,musicHideSeconds,chartSeconds,DEFAULT_HOLD_POWER,DEFAULT_HOLD_SPEED);
    }
    public static WidgetSettings migrate(int version,int mask,int tyreSeconds,int voltageMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax){
        return migrate(version,mask,tyreSeconds,voltageMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,DEFAULT_READ_MS,DEFAULT_READ_MS,DEFAULT_HOLD_POWER_MAX,DEFAULT_HOLD_SECONDS);
    }
    public static WidgetSettings migrate(int version,int mask,int tyreSeconds,int voltageMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax,
                                         int speedMs,int powerMs,int holdPowerMax,int holdSeconds){
        return migrate(version,mask,tyreSeconds,voltageMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedMs,powerMs,holdPowerMax,holdSeconds,DEFAULT_CHART_SECONDS,DEFAULT_CHART_SECONDS);
    }
    public static WidgetSettings migrate(int version,int mask,int tyreSeconds,int voltageMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax,
                                         int speedMs,int powerMs,int holdPowerMax,int holdSeconds,int speedChartSeconds,int powerChartSeconds){
        return migrate(version,mask,tyreSeconds,voltageMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedMs,powerMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,DEFAULT_ORDER,Map.of());
    }
    public static WidgetSettings migrate(int version,int mask,int tyreSeconds,int voltageMs,int musicHideSeconds,int chartSeconds,int holdPowerMin,int holdSpeedMax,
                                         int speedMs,int powerMs,int holdPowerMax,int holdSeconds,int speedChartSeconds,int powerChartSeconds,List<Integer> order,Map<Integer,WidgetCondition> conditions){
        int upgraded=mask;if(version<2)upgraded|=ADDED_IN_V2;if(version<3)upgraded|=ADDED_IN_V3;if(version<4)upgraded|=ADDED_IN_V4;
        if(version<5)holdSeconds=DEFAULT_HOLD_SECONDS;
        if(version<6)upgraded&=~LAMP;
        if(version<7)upgraded&=~(BMS|VOLTAGE_FROM_BMS|POWER_FROM_BMS);
        return new WidgetSettings(upgraded,tyreSeconds,voltageMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedMs,powerMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,order,conditions);
    }
    public boolean enabled(int widget){return (mask&widget)!=0;}
    public WidgetSettings with(int widget,boolean on){return withMask(on?mask|widget:mask&~widget);}
    public WidgetSettings withMask(int value){return new WidgetSettings(value,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,order,conditions);}
    public WidgetSettings intervals(int tyreSeconds,int voltageMs){return readIntervals(tyreSeconds,voltageMs,speedIntervalMs,powerIntervalMs);}
    public WidgetSettings readIntervals(int tyreSeconds,int voltageMs,int speedMs,int powerMs){return new WidgetSettings(mask,tyreSeconds,voltageMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedMs,powerMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,order,conditions);}
    public WidgetSettings musicHide(int seconds){return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,seconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,order,conditions);}
    /** Voltage chart window. */
    public WidgetSettings chart(int seconds){return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,seconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,order,conditions);}
    public WidgetSettings speedChart(int seconds){return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,seconds,powerChartSeconds,order,conditions);}
    public WidgetSettings powerChart(int seconds){return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,seconds,order,conditions);}
    public WidgetSettings hold(int powerMin,int speedMax){return holdRange(powerMin,holdPowerMax,speedMax,holdSeconds);}
    public WidgetSettings holdRange(int powerMin,int powerMax,int speedMax,int seconds){return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,powerMin,speedMax,speedIntervalMs,powerIntervalMs,powerMax,seconds,speedChartSeconds,powerChartSeconds,order,conditions);}
    public WidgetSettings withOrder(List<Integer> value){return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,value,conditions);}
    /** An always-shown condition is the absence of one. */
    public WidgetSettings withCondition(int widget,WidgetCondition condition){
        LinkedHashMap<Integer,WidgetCondition> next=new LinkedHashMap<>(conditions);
        if(condition==null||condition.equals(WidgetCondition.ALWAYS_SHOWN))next.remove(widget);else next.put(widget,condition);
        return new WidgetSettings(mask,tyreIntervalSeconds,voltageIntervalMs,musicHideSeconds,chartSeconds,holdPowerMin,holdSpeedMax,speedIntervalMs,powerIntervalMs,holdPowerMax,holdSeconds,speedChartSeconds,powerChartSeconds,order,next);
    }
    /** Stored condition; the music card falls back to its legacy auto-hide switch, everything else to always shown. */
    public WidgetCondition condition(int widget){
        WidgetCondition stored=conditions.get(widget);if(stored!=null)return stored;
        return widget==MUSIC&&enabled(MUSIC_AUTO_HIDE)?WidgetCondition.onChange(WidgetCondition.TRACK_CHANGE|WidgetCondition.PLAYBACK_CHANGE,musicHideSeconds):WidgetCondition.ALWAYS_SHOWN;
    }
    /** Whether the condition of any enabled widget needs this measurement. */
    public boolean conditionsUse(int check){for(int w:CONDITIONAL)if(enabled(w)&&condition(w).uses(check))return true;return false;}
    public static int index(int widget){for(int i=0;i<CONDITIONAL.length;i++)if(CONDITIONAL[i]==widget)return i;return -1;}
    /**
     * Unknown and repeated entries are dropped, cards missing from the list join the right column in default order, exactly one divider
     * remains, and the notification block is kept in the right column (at its bottom when it had strayed left).
     */
    public static List<Integer> normalizeOrder(List<Integer> value){
        ArrayList<Integer> right=new ArrayList<>(),left=new ArrayList<>();boolean divided=false;
        if(value!=null)for(Integer w:value){
            if(w==null)continue;
            if(w==COLUMN_DIVIDER){divided=true;continue;}
            if(!CARDS.contains(w)||right.contains(w)||left.contains(w))continue;
            (divided?left:right).add(w);
        }
        for(int w:CARDS)if(!right.contains(w)&&!left.contains(w))right.add(w);
        if(left.remove(Integer.valueOf(NOTIFICATIONS)))right.add(0,NOTIFICATIONS);
        ArrayList<Integer> out=new ArrayList<>(right);out.add(COLUMN_DIVIDER);out.addAll(left);
        return List.copyOf(out);
    }
    /** Right-column cards bottom up (before the divider). */
    public List<Integer> rightOrder(){return order.subList(0,order.indexOf(COLUMN_DIVIDER));}
    /** Left-column cards bottom up (after the divider). */
    public List<Integer> leftOrder(){return order.subList(order.indexOf(COLUMN_DIVIDER)+1,order.size());}
    private static Map<Integer,WidgetCondition> normalizeConditions(Map<Integer,WidgetCondition> value){
        if(value==null||value.isEmpty())return Map.of();
        LinkedHashMap<Integer,WidgetCondition> out=new LinkedHashMap<>();
        for(int w:CONDITIONAL){WidgetCondition c=value.get(w);if(c!=null&&!c.equals(WidgetCondition.ALWAYS_SHOWN))out.put(w,c);}
        return Collections.unmodifiableMap(out);
    }
    public String encodeOrder(){StringBuilder b=new StringBuilder();for(int w:order){if(b.length()>0)b.append(',');b.append(w);}return b.toString();}
    public static List<Integer> parseOrder(String text){
        ArrayList<Integer> out=new ArrayList<>();
        if(text!=null)for(String part:text.split(","))try{out.add(Integer.parseInt(part.trim()));}catch(NumberFormatException ignored){}
        return normalizeOrder(out);
    }
    /** The tyre line needs at least one wheel; the voltage card is its own switch. */
    public boolean showsTyres(){return enabled(TYRES)&&(enabled(TYRE_FRONT)||enabled(TYRE_REAR));}
    /** Tyres and voltage are read whenever their card is shown or a condition needs them; TYRE_READ and VOLTAGE_READ survive in stored masks but no longer gate anything. */
    public boolean readsTyres(){return showsTyres()||conditionsUse(WidgetCondition.TYRE_CHECKS);}
    /** With the BMS as the voltage source the vehicle register is left alone; the card and its conditions read the board instead. */
    public boolean readsVoltage(){return !enabled(VOLTAGE_FROM_BMS)&&(enabled(VOLTAGE)||conditionsUse(WidgetCondition.VOLTAGE));}
    /** Speed and power are read for their own cards, for hill-hold detection and for conditions that check them. */
    public boolean readsSpeed(){return enabled(SPEED)||enabled(HILL_HOLD_DODGE)||conditionsUse(WidgetCondition.SPEED);}
    /** With the BMS as the power source the vehicle register is only read for hill hold, which never takes the board's value. */
    public boolean readsPower(){return enabled(HILL_HOLD_DODGE)||!enabled(POWER_FROM_BMS)&&(enabled(POWER)||conditionsUse(WidgetCondition.POWER));}
    public long tyreLimitMs(){return tyreIntervalSeconds*1000L*TYRE_EXPIRY_FACTOR;}
    public long voltageLimitMs(){return (long)voltageIntervalMs*VOLTAGE_EXPIRY_FACTOR;}
    public long speedLimitMs(){return (long)speedIntervalMs*VOLTAGE_EXPIRY_FACTOR;}
    public long powerLimitMs(){return (long)powerIntervalMs*VOLTAGE_EXPIRY_FACTOR;}
    public long musicHideMs(){return musicHideSeconds*1000L;}
    public long chartWindowMs(){return chartSeconds*1000L;}
    public long speedChartWindowMs(){return speedChartSeconds*1000L;}
    public long powerChartWindowMs(){return powerChartSeconds*1000L;}
    public long holdMs(){return holdSeconds*1000L;}
    /** rSpeed reports 0.1 km/h units. */
    public int holdSpeedMaxTenths(){return holdSpeedMax*10;}
    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
}
