package dev.ichinomiya.ninebotenhance.core;

/**
 * What the lamp link is doing and the height the device last reported. The position is never estimated locally: it only ever
 * comes from the device's 0x15 report, so the card and the volume stepper always work from the hoist's own value.
 */
public record LampState(int phase,int position,int speed,int low,int high,String detail){
    /** IDLE: bound, but nothing needs the link right now (Ninebot hidden, no session, no lamp screen), so it is closed. */
    public static final int UNBOUND=0,DENIED=1,CONNECTING=2,AUTHENTICATING=3,READY=4,REJECTED=5,FAILED=6,IDLE=7;
    public static final LampState NONE=new LampState(UNBOUND,-1,-1,-1,-1,"");
    public LampState{
        position=clamp(position);speed=clamp(speed);low=clamp(low);high=clamp(high);
        detail=detail==null?"":detail;
    }
    private static int clamp(int value){return value<0||value>TxLampProtocol.MAX_POSITION?-1:value;}
    public static LampState of(int phase,String detail){return new LampState(phase,-1,-1,-1,-1,detail);}
    public boolean ready(){return phase==READY;}
    public boolean knownPosition(){return ready()&&position>=0;}
    /** Travel limits the device reported, falling back to the full span before the first report. */
    public int lowLimit(){return low<0?TxLampProtocol.MIN_POSITION:low;}
    public int highLimit(){return high<0?TxLampProtocol.MAX_POSITION:high;}
    public LampState withPosition(int value,int speed,int low,int high){return new LampState(READY,value,speed,low,high,"");}
    public LampState withPhase(int value,String detail){return new LampState(value,position,speed,low,high,detail);}
    /** Card text: the height while the hoist is talking to us, otherwise why it is not. */
    public String text(){return knownPosition()?String.valueOf(position):"未连接";}
    public String unit(){return knownPosition()?"%":"";}
    public String describe(){
        String name=switch(phase){
            case UNBOUND->"未绑定";case DENIED->"无蓝牙权限";case CONNECTING->"连接中";case AUTHENTICATING->"认证中";
            case READY->"已连接";case REJECTED->"密码错误";case IDLE->"未连接";default->"连接失败";
        };
        if(!knownPosition())return detail.isEmpty()?name:name+"："+detail;
        return name+" "+position+"%"+(low>=0&&high>=0?"，行程 "+low+"–"+high+"%":"");
    }
}
