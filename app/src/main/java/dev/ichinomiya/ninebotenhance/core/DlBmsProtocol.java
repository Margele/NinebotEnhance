package dev.ichinomiya.ninebotenhance.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Frames of the DL BMS protection board over its FFE0 service. Requests carry the reversed header A5 5A 00 40, a function code, a
 * running serial, packet counters, the content (fields of at most four bytes byte-reversed, longer content as is) and a 16-bit sum;
 * answers arrive with the header in natural order. Everything but the FC00 key exchange is AES-128-CBC encrypted with key and
 * IV cut from the 32-byte secp256k1 shared secret, zero padded to whole blocks. Only FC00 and FC17 (read the board data) are sent.
 */
public final class DlBmsProtocol {
    public static final String SERVICE="0000ffe0-0000-1000-8000-00805f9b34fb",CHAR_WRITE="0000ffe1-0000-1000-8000-00805f9b34fb",CHAR_NOTIFY="0000ffe2-0000-1000-8000-00805f9b34fb";
    public static final String CCCD="00002902-0000-1000-8000-00805f9b34fb";
    public static final BmsProtocol.Endpoint ENDPOINT=new BmsProtocol.Endpoint(SERVICE,CHAR_WRITE,CHAR_NOTIFY,false,false);
    public static final int FC_KEY=0x00,FC_NAME=0x01,FC_DATA=0x17;
    /** Advertisement: company id 0x6C64 ("dl") followed by "kjmk", the MAC and a flag byte; the device name is DL-BMS. */
    public static final int MANUFACTURER_ID=0x6C64;
    public static final String DEVICE_NAME="DL-BMS";
    public static final int HEADER_LENGTH=13,TRAILER_LENGTH=2;
    public record Response(int fc,int serial,int total,int index,byte[] content){}
    public static byte[] frame(int fc,byte[] content,int serial){
        ByteArrayOutputStream o=new ByteArrayOutputStream();
        o.write(0xa5);o.write(0x5a);o.write(0x00);o.write(0x40);o.write(fc&0xff);
        le16(o,serial);le16(o,1);le16(o,1);le16(o,content.length);
        if(content.length<=4)for(int i=content.length-1;i>=0;i--)o.write(content[i]);else o.write(content,0,content.length);
        byte[] body=o.toByteArray();int sum=checksum(body,body.length);
        o.write(sum&0xff);o.write((sum>>8)&0xff);
        return o.toByteArray();
    }
    public static int checksum(byte[] data,int length){int sum=0;for(int i=0;i<length;i++)sum+=data[i]&0xff;return sum&0xffff;}
    private static void le16(ByteArrayOutputStream o,int value){o.write(value&0xff);o.write((value>>8)&0xff);}
    /** Total length of the response frame that starts at the buffer, or -1 while the header is incomplete. */
    public static int frameLength(byte[] buffer,int available){
        if(available<HEADER_LENGTH)return -1;
        return HEADER_LENGTH+u16(buffer,11)+TRAILER_LENGTH;
    }
    public static boolean responseHeader(byte[] buffer,int available){return available>=4&&(buffer[0]&0xff)==0x5a&&(buffer[1]&0xff)==0xa5&&(buffer[2]&0xff)==0x00&&(buffer[3]&0xff)==0x40;}
    /** One complete answer; null when the header, length or checksum does not hold. */
    public static Response parse(byte[] buffer,int available){
        if(!responseHeader(buffer,available))return null;
        int length=frameLength(buffer,available);if(length<0||length>available)return null;
        int expected=u16(buffer,length-2);if(checksum(buffer,length-2)!=expected)return null;
        byte[] content=new byte[length-HEADER_LENGTH-TRAILER_LENGTH];System.arraycopy(buffer,HEADER_LENGTH,content,0,content.length);
        return new Response(buffer[4]&0xff,u16(buffer,5),u16(buffer,7),u16(buffer,9),content);
    }
    public static byte[] encrypt(byte[] key,byte[] iv,byte[] plain){
        int padded=(plain.length+15)/16*16;byte[] input=new byte[padded];System.arraycopy(plain,0,input,0,plain.length);
        return cipher(Cipher.ENCRYPT_MODE,key,iv,input);
    }
    public static byte[] decrypt(byte[] key,byte[] iv,byte[] data){
        if(data.length==0||data.length%16!=0)throw new IllegalArgumentException("cipher text is not whole blocks");
        return cipher(Cipher.DECRYPT_MODE,key,iv,data);
    }
    private static byte[] cipher(int mode,byte[] key,byte[] iv,byte[] input){
        try{Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return c.doFinal(input);}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public static byte[] key(byte[] shared){byte[] k=new byte[16];System.arraycopy(shared,0,k,0,16);return k;}
    public static byte[] iv(byte[] shared){byte[] v=new byte[16];System.arraycopy(shared,16,v,0,16);return v;}
    /** The FC17 answer in either of its two shapes: the 142-byte one with the name, or the 110-byte one without. */
    public static BmsData parseData(byte[] c,long at){
        if(c==null)return null;
        // The full shape opens with the ASCII name, the truncated one with the MOS status bits; the lengths alone overlap.
        boolean full=c.length>=142||(c.length>=78&&(c[0]&0xff)>=0x20&&(c[0]&0xff)<0x7f);
        if(full)return parseFull(c,at);
        if(c.length>=46)return parseShort(c,at);
        return null;
    }
    private static BmsData parseFull(byte[] c,long at){
        String name=ascii(c,0,32);int mos=c[32]&0xff,cells=c[33]&0xff;
        float capacity=u32(c,34)/10f,remaining=u32(c,38)/10f,volts=u16(c,42)/10f,amps=s16(c,44)/10f;
        int watts=s32(c,46),soc=u16(c,50);
        int tempCount=Math.min(9,c[52]&0xff);int[] temps=new int[tempCount];for(int i=0;i<tempCount;i++)temps[i]=(byte)c[53+i];
        int maxCell=u16(c,62),minCell=u16(c,64),avgCell=u16(c,66),diff=u16(c,68);
        float cycleAh=u32(c,70)/10f;int cycles=(int)Math.min(Integer.MAX_VALUE,u32(c,74));
        int count=Math.min(Math.min(cells,32),(c.length-78)/2);int[] cell=new int[count];
        for(int i=0;i<count;i++){int v=u16(c,78+i*2);if(v>10000)v=Math.round(v/13.4f);cell[i]=v;}
        return new BmsData(name,mos,cells,capacity,remaining,volts,amps,watts,soc,temps,maxCell,minCell,avgCell,diff,cycleAh,cycles,cell,at);
    }
    private static BmsData parseShort(byte[] c,long at){
        int mos=c[0]&0xff,cells=c[1]&0xff;
        float capacity=u32(c,2)/10f,remaining=u32(c,6)/10f,volts=u16(c,10)/10f,amps=s16(c,12)/10f;
        int watts=s32(c,14),soc=u16(c,18);
        int tempCount=Math.min(9,c[20]&0xff);int[] temps=new int[tempCount];for(int i=0;i<tempCount;i++)temps[i]=(byte)c[21+i];
        int count=Math.min(Math.min(cells,32),(c.length-46)/2);int[] cell=new int[count];int max=0,min=Integer.MAX_VALUE,sum=0,used=0;
        for(int i=0;i<count;i++){int v=u16(c,46+i*2);if(v==0)continue;if(v>10000)v=Math.round(v/13.4f);cell[i]=v;max=Math.max(max,v);min=Math.min(min,v);sum+=v;used++;}
        int avg=used==0?0:sum/used;if(used==0)min=0;
        return new BmsData("",mos,cells,capacity,remaining,volts,amps,watts,soc,temps,max,min,avg,used==0?0:max-min,0,0,cell,at);
    }
    private static String ascii(byte[] c,int offset,int length){int end=offset;while(end<offset+length&&c[end]!=0)end++;return new String(c,offset,end-offset,StandardCharsets.US_ASCII).trim();}
    static int u16(byte[] b,int i){return (b[i]&0xff)|((b[i+1]&0xff)<<8);}
    static int s16(byte[] b,int i){return (short)u16(b,i);}
    static long u32(byte[] b,int i){return (u16(b,i)|((long)u16(b,i+2)<<16))&0xffffffffL;}
    static int s32(byte[] b,int i){return (int)u32(b,i);}
    /** Manufacturer data as Android hands it out: the company id separately, the value starting at "kjmk". */
    public static boolean advertisement(int companyId,byte[] data){
        return companyId==MANUFACTURER_ID&&data!=null&&data.length>=4&&data[0]=='k'&&data[1]=='j'&&data[2]=='m'&&data[3]=='k';
    }
    public static boolean deviceName(String name){return name!=null&&name.trim().toUpperCase(java.util.Locale.ROOT).startsWith(DEVICE_NAME);}
    private DlBmsProtocol(){}
}
