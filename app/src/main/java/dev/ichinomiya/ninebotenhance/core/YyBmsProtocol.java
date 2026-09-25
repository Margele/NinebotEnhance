package dev.ichinomiya.ninebotenhance.core;

import java.util.List;

/** The 彦阳 boards: one Modbus RTU read of the holding registers, answered with a 189-byte frame the module takes apart field by field. */
public final class YyBmsProtocol extends BufferedBmsProtocol {
    private static final int DATA=3,REGISTER_COUNT=92,RESPONSE=189;
    private static final byte[] READ=request();
    private static final BmsProtocol.Endpoint ENDPOINT=new Endpoint(FFE0,null,null,true,true),FALLBACK=new Endpoint(FF00,null,null,true,true),
            NUS=new Endpoint(NUS_SERVICE,NUS_WRITE,NUS_NOTIFY,false,false);
    private static byte[] request(){
        byte[] frame=new byte[8];
        frame[0]=1;frame[1]=3;frame[2]=0;frame[3]=0x4b;frame[4]=0;frame[5]=0x5c;
        int crc=crc16(frame,0,6);frame[6]=(byte)(crc&0xff);frame[7]=(byte)((crc>>>8)&0xff);
        return frame;
    }
    static boolean name(String label){
        if(label==null)return false;String lower=label.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("yanyang")||lower.contains("yybms")||lower.contains("yy-bms")||lower.contains("hlk-b40")||lower.contains("b40");
    }
    @Override public int id(){return BmsSettings.PROTOCOL_YY;}
    @Override public List<Endpoint> endpoints(){return List.of(ENDPOINT,FALLBACK,NUS);}
    @Override public byte[][] begin(long now){return new byte[][]{READ};}
    @Override public byte[][] poll(long now,long lastDataAt){return new byte[][]{READ};}
    @Override public long silenceMs(int pollMs){return pollMs*3L+2000;}
    @Override protected int headerLength(){return 2;}
    @Override protected boolean atHeader(int offset){
        int func=buffer[offset+1]&0xff;
        return (buffer[offset]&0xff)==1&&(func==3||func==0x83);
    }
    @Override protected int frameLength(){
        if(length<3)return NEED_MORE;
        int size=(buffer[1]&0xff)==0x83?5:(buffer[2]&0xff)+5;
        if(length<size)return NEED_MORE;
        return crc16(buffer,0,size-2)==u16(buffer,size-2)?size:BAD;
    }
    @Override protected BmsData take(int size,long now){
        if(size!=RESPONSE||buffer[0]!=1||buffer[1]!=3||(buffer[2]&0xff)!=REGISTER_COUNT*2)return null;
        int cells=buffer[DATA]&0xff;
        if(cells<1||cells>32)return null;
        float volts=u32(buffer,DATA+2)*0.001f,amps=s32(buffer,DATA+6)*0.01f;
        float capacity=u16(buffer,DATA+86)*0.1f,remaining=u16(buffer,DATA+88)*0.1f;
        if(volts<1||volts>1000||Math.abs(amps)>5000||capacity>1000000)return null;
        int[] cell=new int[cells];
        for(int i=0;i<cells;i++)cell[i]=u16(buffer,DATA+12+2*i);
        CellStats stats=statistics(cell,cells);
        int[] temps={buffer[DATA+77]-40,buffer[DATA+76]-40};
        long run=u32(buffer,DATA+154);
        int mos=(run>>>28&1)==0?1:0;
        mos|=(run>>>29&1)==0?2:0;
        return new BmsData("",mos,cells,capacity,remaining,volts,amps,Math.round(volts*amps),
                Math.min(100,buffer[DATA+90]&0xff),temps,stats.maxMv(),stats.minMv(),stats.avgMv(),stats.diffMv(),0,0,cell,now);
    }
}
