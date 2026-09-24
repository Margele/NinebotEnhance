package dev.ichinomiya.ninebotenhance.core;

/** What the BMS link is doing and the last reading it delivered; readings only ever come from the device. */
public record BmsState(int phase,BmsData data,String detail){
    /** IDLE: bound, but nothing needs the link right now, so it is closed. */
    public static final int UNBOUND=0,DENIED=1,CONNECTING=2,HANDSHAKE=3,READY=4,FAILED=5,IDLE=6;
    public static final BmsState NONE=new BmsState(UNBOUND,BmsData.EMPTY,"");
    public BmsState{data=data==null?BmsData.EMPTY:data;detail=detail==null?"":detail;}
    public static BmsState of(int phase,String detail){return new BmsState(phase,BmsData.EMPTY,detail);}
    public boolean ready(){return phase==READY;}
    /** Connected and holding a reading no older than the limit. */
    public boolean connected(long now,long limitMs){return ready()&&data.known()&&now-data.at()<=limitMs;}
    public BmsState withData(BmsData value){return new BmsState(READY,value,"");}
    public BmsState withPhase(int value,String detail){return new BmsState(value,data,detail);}
    public String describe(){
        String name=switch(phase){
            case UNBOUND->"未绑定";case DENIED->"无蓝牙权限";case CONNECTING->"连接中";case HANDSHAKE->"握手中";case READY->"已连接";case IDLE->"未连接";default->"连接失败";
        };
        if(!ready()||!data.known())return detail.isEmpty()?name:name+"："+detail;
        return name+" "+data.describe();
    }
}
