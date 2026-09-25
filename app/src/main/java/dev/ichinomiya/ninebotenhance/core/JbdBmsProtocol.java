package dev.ichinomiya.ninebotenhance.core;

import java.util.List;

/** The JBD boards: a seven-byte DD A5 request and a DD answer carrying the basic pack reading and the cell voltages separately. */
public final class JbdBmsProtocol extends BufferedBmsProtocol {
    private static final byte[] BASIC=request(0x03),CELLS=request(0x04);
    private static final BmsProtocol.Endpoint ENDPOINT=new Endpoint(FF00,FF02,FF01,false,false);
    private static byte[] request(int cmd){
        byte[] frame=new byte[7];
        frame[0]=(byte)0xdd;frame[1]=(byte)0xa5;frame[2]=(byte)cmd;frame[3]=0;
        int sum=(-cmd)&0xffff;frame[4]=(byte)(sum>>>8);frame[5]=(byte)sum;frame[6]=0x77;
        return frame;
    }
    static boolean name(String label){
        if(label==null)return false;String lower=label.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("jbd")||lower.contains("xiaoxiang");
    }
    private BmsData basic;private int[] cells=new int[0];private int declaredCells;
    @Override public void reset(){super.reset();basic=null;cells=new int[0];declaredCells=0;}
    @Override public int id(){return BmsSettings.PROTOCOL_JBD;}
    @Override public List<Endpoint> endpoints(){return List.of(ENDPOINT);}
    @Override public byte[][] begin(long now){return new byte[][]{BASIC};}
    @Override public byte[][] poll(long now,long lastDataAt){return new byte[][]{BASIC};}
    @Override public byte[] followUp(long now,long lastDataAt){return CELLS;}
    @Override public long followUpDelayMs(){return 120;}
    @Override public long silenceMs(int pollMs){return pollMs*3L+2000;}
    @Override protected int headerLength(){return 1;}
    @Override protected boolean atHeader(int offset){return (buffer[offset]&0xff)==0xdd;}
    @Override protected int frameLength(){
        if(length<4)return NEED_MORE;
        int size=(buffer[3]&0xff)+7;
        if(length<size)return NEED_MORE;
        if((buffer[size-1]&0xff)!=0x77)return BAD;
        int stored=((buffer[size-3]&0xff)<<8)|(buffer[size-2]&0xff);
        if(stored==sum16(buffer,2,size-5)||stored==sum16(buffer,1,size-4))return size;
        return BAD;
    }
    @Override protected BmsData take(int size,long now){
        if((buffer[2]&0xff)!=0)return null;
        int cmd=buffer[1]&0xff;
        if(cmd==0x03)return basicFrame(size,now);
        if(cmd==0x04)return cellFrame(size,now);
        return null;
    }
    private BmsData basicFrame(int size,long now){
        int payload=size-7;if(payload<23)return null;
        float volts=u16be(buffer,4)*0.01f,amps=-s16be(buffer,6)*0.01f;
        float capacity=u16be(buffer,10)*0.01f,remaining=u16be(buffer,8)*0.01f;
        if(volts>1000||Math.abs(amps)>5000||capacity>1000000)return null;
        int declared=buffer[25]&0xff;if(declared>0&&declared<=32)declaredCells=declared;
        int declaredTemps=buffer[26]&0xff;
        int[] temps=new int[Math.max(0,Math.min(Math.min(declaredTemps,6),(payload-23)/2))];int count=0;
        for(int i=0;i<temps.length;i++){
            int raw=u16be(buffer,27+2*i),t=(raw-2731)/10;
            if(t<-80||t>200)continue;
            temps[count++]=t;
        }
        int[] out=new int[count];System.arraycopy(temps,0,out,0,count);
        basic=new BmsData("",buffer[24]&0xff,cells.length>0?cells.length:declaredCells,capacity,remaining,volts,amps,
                Math.round(volts*amps),Math.min(100,buffer[23]&0xff),out,0,0,0,0,0,u16be(buffer,12),clone(cells),now);
        return basic;
    }
    private BmsData cellFrame(int size,long now){
        int payload=size-7;
        if(payload<2||payload%2!=0)return null;
        cells=new int[Math.min(payload/2,32)];
        for(int i=0;i<cells.length;i++)cells[i]=u16be(buffer,4+2*i);
        if(basic==null)return null;
        CellStats stats=statistics(cells,cells.length);
        basic=new BmsData("",basic.mos(),cells.length,basic.capacityAh(),basic.remainingAh(),basic.volts(),basic.amps(),
                basic.watts(),basic.soc(),basic.temps(),stats.maxMv(),stats.minMv(),stats.avgMv(),stats.diffMv(),
                basic.cycleAh(),basic.cycles(),clone(cells),now);
        return basic;
    }
    private static int[] clone(int[] source){return source.clone();}
}
