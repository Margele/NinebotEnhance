package dev.ichinomiya.ninebotenhance.core;

import java.util.Arrays;

/**
 * Notification assembly shared by the plain protocols: keep the pieces, scan for the header the board announces, ask the
 * board's own framing how long the frame is and hand the whole frame to the parser. A header that never arrives, a frame that
 * does not hold and a frame left unfinished for longer than a second are all dropped so the stream resynchronises by itself.
 */
abstract class BufferedBmsProtocol implements BmsProtocol {
    static final int NEED_MORE=-1,BAD=-2;
    /** Cell count and the highest, lowest, spread and mean of the cells that read as a real voltage. */
    record CellStats(int count,int maxMv,int minMv,int diffMv,int avgMv){}
    protected byte[] buffer=new byte[512];
    protected int length;
    private long lastAt;
    @Override public void reset(){length=0;lastAt=0;}
    @Override public BmsData accept(byte[] value,long now){
        if(value==null||value.length==0)return null;
        if(now-lastAt>1000)length=0;
        lastAt=now;
        if(length+value.length>buffer.length)buffer=Arrays.copyOf(buffer,Math.max(buffer.length*2,length+value.length));
        System.arraycopy(value,0,buffer,length,value.length);length+=value.length;
        BmsData out=null;
        while(length>=headerLength()){
            int start=header();
            if(start<0){keepTail();break;}
            if(start>0)drop(start);
            int size=frameLength();
            if(size==NEED_MORE)break;
            if(size==BAD){drop(1);continue;}
            if(size>length)break;
            BmsData parsed=take(size,now);
            if(parsed!=null)out=parsed;
            drop(size);
        }
        return out;
    }
    private int header(){for(int i=0;i<=length-headerLength();i++)if(atHeader(i))return i;return -1;}
    private void keepTail(){int keep=Math.min(length,Math.max(0,headerLength()-1));if(keep<length)drop(length-keep);}
    protected void drop(int count){System.arraycopy(buffer,count,buffer,0,length-count);length-=count;}
    protected abstract int headerLength();
    protected abstract boolean atHeader(int offset);
    protected abstract int frameLength();
    protected abstract BmsData take(int size,long now);
    /** CRC16-MODBUS, reflected, low byte first. */
    protected static int crc16(byte[] data,int offset,int length){
        int crc=0xffff;
        for(int i=offset;i<offset+length;i++){
            crc^=data[i]&0xff;
            for(int bit=0;bit<8;bit++)crc=(crc&1)!=0?(crc>>>1)^0xa001:crc>>>1;
        }
        return crc&0xffff;
    }
    /** The JBD family's running sum: zero minus the covered bytes, held big-endian on the wire. */
    protected static int sum16(byte[] data,int offset,int length){
        int sum=0;
        for(int i=offset;i<offset+length;i++)sum=(sum+(data[i]&0xff))&0xffff;
        return (-sum)&0xffff;
    }
    /** Statistics over the cells between 100 and 6500 mV; a range that holds none counts no cell at all. */
    protected static CellStats statistics(int[] cells,int count){
        int max=0,min=Integer.MAX_VALUE,sum=0,used=0;
        for(int i=0;i<count&&i<cells.length;i++){
            int mv=cells[i];
            if(mv<100||mv>6500)continue;
            used++;sum+=mv;max=Math.max(max,mv);min=Math.min(min,mv);
        }
        if(used==0)return new CellStats(0,0,0,0,0);
        return new CellStats(used,max,min,max-min,sum/used);
    }
    protected static int u16(byte[] b,int i){return (b[i]&0xff)|((b[i+1]&0xff)<<8);}
    protected static int s16(byte[] b,int i){return (short)u16(b,i);}
    protected static long u32(byte[] b,int i){return (u16(b,i)|((long)u16(b,i+2)<<16))&0xffffffffL;}
    protected static int s32(byte[] b,int i){return (int)u32(b,i);}
    protected static int u16be(byte[] b,int i){return ((b[i]&0xff)<<8)|(b[i+1]&0xff);}
    protected static int s16be(byte[] b,int i){return (short)u16be(b,i);}
}
