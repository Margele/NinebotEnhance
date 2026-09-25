package dev.ichinomiya.ninebotenhance.core;

/**
 * Frames of the 摩灯客 headlight ESC (the “蓝牙电调控制” mini program): {@code AA 55}, the ASCII version {@code 0001}, a type byte, the
 * data, a Modbus CRC16 over everything before it with the high byte first, then {@code 0D 0A}. Keyed commands carry the six
 * character password at the start of their data. The module sends only two types: A9, the firmware query, as the liveness probe
 * after connecting, and A3 with direction 3, which slides the hoist to an absolute 0–100 height. Every other type (A0 boot
 * effect, A1 lights, A4–A8, AA–AC, DB, CC OTA) changes a device setting and is never sent. The device acknowledges each keyed
 * command with EE (type, result) and answers A9 with B9; it never reports its height, so the module remembers the last target.
 */
public final class EscLampProtocol {
    public static final String SERVICE="0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String CHAR_WRITE="0000fff2-0000-1000-8000-00805f9b34fb";
    public static final String CHAR_NOTIFY="0000fff1-0000-1000-8000-00805f9b34fb";
    public static final int TYPE_MOVE=0xA3,TYPE_QUERY=0xA9,TYPE_ACK=0xEE,TYPE_INFO=0xB9;
    public static final int DIR_STOP=0,DIR_UP=1,DIR_DOWN=2,DIR_SLIDE=3,ACCEPTED=1;
    public static final int MIN_POSITION=0,MAX_POSITION=100,PASSWORD_LENGTH=6;
    private static final byte[] VERSION={'0','0','0','1'};
    private static final int HEAD=6,TAIL=4;
    /** CRC16 Modbus: polynomial 0xA001 reflected, initial 0xFFFF, over {@code length} bytes from {@code offset}. */
    public static int crc16(byte[] data,int offset,int length){
        int crc=0xffff;
        for(int i=offset;i<offset+length;i++){
            crc^=data[i]&0xff;
            for(int bit=0;bit<8;bit++)crc=(crc&1)!=0?(crc>>>1)^0xA001:crc>>>1;
        }
        return crc&0xffff;
    }
    public static byte[] frame(int type,byte... data){
        byte[] body=data==null?new byte[0]:data;
        if(type<0||type>0xff)throw new IllegalArgumentException("电调帧类型超出范围");
        byte[] out=new byte[HEAD+1+body.length+TAIL];
        out[0]=(byte)0xAA;out[1]=0x55;System.arraycopy(VERSION,0,out,2,VERSION.length);out[HEAD]=(byte)type;
        System.arraycopy(body,0,out,HEAD+1,body.length);
        int end=HEAD+1+body.length,crc=crc16(out,0,end);
        out[end]=(byte)(crc>>8);out[end+1]=(byte)crc;out[end+2]=0x0D;out[end+3]=0x0A;
        return out;
    }
    /** Six printable ASCII characters; the vendor default is 123456. */
    public static boolean validPassword(String password){
        if(password==null||password.length()!=PASSWORD_LENGTH)return false;
        for(int i=0;i<PASSWORD_LENGTH;i++){char c=password.charAt(i);if(c<0x21||c>0x7e)return false;}
        return true;
    }
    /** A3 with direction 3: slide to an absolute height. The device executes whatever it is told; nothing is read back. */
    public static byte[] moveTo(String password,int position){
        if(!validPassword(password))throw new IllegalArgumentException("电调密码必须是 6 个字符");
        if(position<MIN_POSITION||position>MAX_POSITION)throw new IllegalArgumentException("电调高度为 0–100");
        byte[] data=new byte[PASSWORD_LENGTH+2];
        for(int i=0;i<PASSWORD_LENGTH;i++)data[i]=(byte)password.charAt(i);
        data[PASSWORD_LENGTH]=DIR_SLIDE;data[PASSWORD_LENGTH+1]=(byte)position;
        return frame(TYPE_MOVE,data);
    }
    /** A9: firmware information, the only command without a password; answered with B9. */
    public static byte[] query(){return frame(TYPE_QUERY);}
    public static int clampPosition(int value){return Math.max(MIN_POSITION,Math.min(MAX_POSITION,value));}
    /** One device frame: its type and data. */
    public record Response(int type,byte[] data){}
    /** The first frame in the buffer; null when the header, version, tail or CRC do not hold. */
    public static Response parse(byte[] value){
        if(value==null||value.length<HEAD+1+TAIL)return null;
        if((value[0]&0xff)!=0xAA||(value[1]&0xff)!=0x55)return null;
        for(int i=0;i<VERSION.length;i++)if(value[2+i]!=VERSION[i])return null;
        int end=value.length-TAIL;
        if((value[end+2]&0xff)!=0x0D||(value[end+3]&0xff)!=0x0A)return null;
        int crc=crc16(value,0,end);
        if((value[end]&0xff)!=(crc>>8)||(value[end+1]&0xff)!=(crc&0xff))return null;
        byte[] data=new byte[end-HEAD-1];System.arraycopy(value,HEAD+1,data,0,data.length);
        return new Response(value[HEAD]&0xff,data);
    }
    /** Whether the frame is the EE receipt for the given command type with result 1. */
    public static boolean accepted(Response response,int type){
        return response!=null&&response.type()==TYPE_ACK&&response.data().length>=2&&(response.data()[0]&0xff)==type&&(response.data()[1]&0xff)==ACCEPTED;
    }
    /** Whether the frame is an EE receipt for the given command type at all, accepted or not. */
    public static boolean receipt(Response response,int type){
        return response!=null&&response.type()==TYPE_ACK&&response.data().length>=1&&(response.data()[0]&0xff)==type;
    }
    private EscLampProtocol(){}
}
