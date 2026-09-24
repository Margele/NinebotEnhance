import dev.ichinomiya.ninebotenhance.core.*;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

final class BmsTests {
    private static byte[] bytes(int... values){byte[] out=new byte[values.length];for(int i=0;i<values.length;i++)out[i]=(byte)values[i];return out;}
    static void run(){
        // Curve arithmetic against the published generator multiples.
        Secp256k1.Point two=Secp256k1.multiply(BigInteger.valueOf(2),Secp256k1.G);
        CoreTests.check(two.x().equals(new BigInteger("C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5",16))
                &&two.y().equals(new BigInteger("1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A",16)),"2G matches the secp256k1 reference");
        Secp256k1.Point three=Secp256k1.multiply(BigInteger.valueOf(3),Secp256k1.G);
        CoreTests.check(three.x().equals(new BigInteger("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",16)),"3G matches the secp256k1 reference");
        CoreTests.check(Secp256k1.multiply(Secp256k1.N,Secp256k1.G)==null&&Secp256k1.onCurve(Secp256k1.G),"nG is the point at infinity and G lies on the curve");
        SecureRandom random=new SecureRandom();
        byte[] a=Secp256k1.privateKey(random),b=Secp256k1.privateKey(random);
        byte[] pa=Secp256k1.publicKey(a),pb=Secp256k1.publicKey(b);
        CoreTests.check(pa.length==64&&pb.length==64&&Arrays.equals(Secp256k1.shared(a,pb),Secp256k1.shared(b,pa)),"both sides of the handshake derive the same 32-byte secret");
        CoreTests.rejects(()->Secp256k1.decode(new byte[64]),"a point off the curve is refused");
        // Request frames: reversed header, little endian counters, content of at most four bytes reversed, checksum over everything before it.
        byte[] data=DlBmsProtocol.frame(DlBmsProtocol.FC_DATA,new byte[0],1);
        CoreTests.check(Arrays.equals(data,bytes(0xA5,0x5A,0x00,0x40,0x17,0x01,0x00,0x01,0x00,0x01,0x00,0x00,0x00,0x59,0x01)),"the FC17 request is framed with its running sum: "+hex(data));
        byte[] param=DlBmsProtocol.frame(0x05,bytes(0x12,0x34),7);
        CoreTests.check(param[13]==0x34&&param[14]==0x12&&(param[11]&0xff)==2,"short content is byte reversed");
        byte[] key=new byte[64];for(int i=0;i<64;i++)key[i]=(byte)i;byte[] exchange=DlBmsProtocol.frame(DlBmsProtocol.FC_KEY,key,2);
        CoreTests.check(exchange.length==13+64+2&&exchange[13]==0&&exchange[76]==63,"a 64-byte public key is sent as is");
        // Answers: natural header; parse, length and checksum checks.
        byte[] content=bytes(0x42,0x4C,0x00);byte[] answer=answer(DlBmsProtocol.FC_NAME,3,content);
        DlBmsProtocol.Response r=DlBmsProtocol.parse(answer,answer.length);
        CoreTests.check(r!=null&&r.fc()==DlBmsProtocol.FC_NAME&&r.serial()==3&&Arrays.equals(r.content(),content),"an answer frame parses back to its fields");
        CoreTests.check(DlBmsProtocol.frameLength(answer,answer.length)==answer.length&&DlBmsProtocol.frameLength(answer,5)==-1&&DlBmsProtocol.parse(answer,answer.length-1)==null,"the frame length comes from the header and a short buffer does not parse");
        byte[] broken=answer.clone();broken[broken.length-1]^=1;
        CoreTests.check(DlBmsProtocol.parse(broken,broken.length)==null,"a wrong checksum is dropped");
        // AES-128-CBC with zero padding round-trips and pads to whole blocks only when needed.
        byte[] shared=new byte[32];for(int i=0;i<32;i++)shared[i]=(byte)(i*7);
        byte[] plain=bytes(1,2,3,4,5);byte[] cipher=DlBmsProtocol.encrypt(DlBmsProtocol.key(shared),DlBmsProtocol.iv(shared),plain);
        byte[] back=DlBmsProtocol.decrypt(DlBmsProtocol.key(shared),DlBmsProtocol.iv(shared),cipher);
        CoreTests.check(cipher.length==16&&Arrays.equals(Arrays.copyOf(back,5),plain)&&back[5]==0,"zero padded CBC round trip");
        CoreTests.check(DlBmsProtocol.encrypt(DlBmsProtocol.key(shared),DlBmsProtocol.iv(shared),new byte[32]).length==32,"aligned input is not padded further");
        // FC17: the documented sample values in the full 142-byte shape.
        byte[] full=new byte[142];System.arraycopy("BAT3".getBytes(),0,full,0,4);full[32]=0x0f;full[33]=20;
        put32(full,34,300);put32(full,38,264);put16(full,42,804);put16(full,44,3);put32(full,46,24);put16(full,50,88);full[52]=1;full[53]=21;
        put16(full,62,4032);put16(full,64,4010);put16(full,66,4024);put16(full,68,22);put32(full,70,125);put32(full,74,5);
        for(int i=0;i<20;i++)put16(full,78+i*2,4010+i);
        BmsData d=DlBmsProtocol.parseData(full,1000);
        CoreTests.check(d!=null&&d.name().equals("BAT3")&&d.cells()==20&&d.volts()==80.4f&&d.amps()==0.3f&&d.watts()==24&&d.soc()==88&&d.cycles()==5&&d.diffMv()==22&&d.temps()[0]==21&&d.cellMv().length==20&&d.cellMv()[19]==4029&&d.known(),"the full FC17 shape parses: "+d.describe());
        put16(full,44,0xfffe);put32(full,46,0xffffffe8L);put16(full,78,4020*134/10);
        BmsData charging=DlBmsProtocol.parseData(full,2000);
        CoreTests.check(charging.amps()==-0.2f&&charging.watts()==-24&&charging.charging()&&charging.cellMv()[0]==4020,"signed current and power, and old-firmware cell scaling");
        byte[] shortShape=new byte[110];shortShape[0]=0x0f;shortShape[1]=20;put32(shortShape,2,300);put32(shortShape,6,264);put16(shortShape,10,804);put16(shortShape,12,3);put32(shortShape,14,24);put16(shortShape,18,88);shortShape[20]=1;shortShape[21]=25;
        for(int i=0;i<20;i++)put16(shortShape,46+i*2,4000+i);
        BmsData s=DlBmsProtocol.parseData(shortShape,3000);
        CoreTests.check(s!=null&&s.name().isEmpty()&&s.volts()==80.4f&&s.soc()==88&&s.maxCellMv()==4019&&s.minCellMv()==4000&&s.diffMv()==19&&s.temps()[0]==25,"the truncated shape derives the cell statistics");
        CoreTests.check(DlBmsProtocol.parseData(new byte[10],1)==null&&DlBmsProtocol.advertisement(0x6C64,"kjmk".getBytes())&&!DlBmsProtocol.advertisement(0x6C64,"abcd".getBytes())&&DlBmsProtocol.deviceName("DL-BMS")&&!DlBmsProtocol.deviceName("MOTORE"),"short buffers are rejected and the advertisement is recognised");
        // Settings, state and the card layout.
        BmsSettings settings=new BmsSettings("b4:f5:f6:77:77:0b",2750);
        CoreTests.check(settings.bound()&&settings.mac().equals("B4:F5:F6:77:77:0B")&&settings.pollMs()==2500&&new BmsSettings("",99999).pollMs()==BmsSettings.MAX_POLL_MS&&!BmsSettings.NONE.bound(),"BMS settings normalise the address and step the poll interval");
        BmsState state=BmsState.NONE.withData(d);
        CoreTests.check(state.ready()&&state.connected(1500,settings.limitMs())&&!state.connected(20000,settings.limitMs())&&!BmsState.of(BmsState.CONNECTING,"").connected(1500,settings.limitMs())&&state.describe().startsWith("已连接 SOC 88%")&&BmsState.of(BmsState.IDLE,"").describe().equals("未连接")&&!BmsState.of(BmsState.IDLE,"").connected(1500,settings.limitMs()),"the state reports a fresh reading and expires an old one");
        BmsCard.Layout layout=BmsCard.parse("1,2|4,4,9|7");
        CoreTests.check(layout.rows().get(0).equals(List.of(1,2))&&layout.rows().get(1).equals(List.of(4))&&layout.rows().get(2).equals(List.of(7))&&layout.encode().equals("1,2|4|7")&&layout.height()==3*BmsCard.ROW_HEIGHT,"the layout drops repeats and unknown fields and keeps three rows");
        BmsCard.Layout sparse=BmsCard.parse("|3||");
        CoreTests.check(sparse.rowCount()==1&&sparse.height()==BmsCard.ROW_HEIGHT&&sparse.rowOf(3)==1&&sparse.rowOf(1)==-1&&BmsCard.parse("").equals(BmsCard.DEFAULT)&&BmsCard.parse("|||").height()==BmsCard.ROW_HEIGHT,"empty rows are hidden and an empty layout still has one row");
        CoreTests.check(BmsCard.value(BmsCard.VOLTAGE,d).equals("80.4")&&BmsCard.value(BmsCard.SOC,BmsData.EMPTY).equals("--")&&BmsCard.unit(BmsCard.DIFF).equals("mV")&&BmsCard.label(BmsCard.CYCLES).equals("循环次数"),"field texts");
        CoreTests.check(WidgetSettings.CARDS.contains(WidgetSettings.BMS)&&(WidgetSettings.OFF_BY_DEFAULT&WidgetSettings.BMS)!=0&&!WidgetSettings.DEFAULT.enabled(WidgetSettings.BMS)&&WidgetSettings.index(WidgetSettings.BMS)>=0,"the BMS card exists, is off by default and takes a condition");
        CoreTests.check(!WidgetSettings.migrate(6,WidgetSettings.ALL,30,1000,5,30).enabled(WidgetSettings.BMS)&&WidgetSettings.migrate(7,WidgetSettings.ALL,30,1000,5,30).enabled(WidgetSettings.BMS),"saves from before the BMS existed do not inherit it");
        WidgetCondition bmsOnly=new WidgetCondition(WidgetCondition.WHILE,0,5,WidgetCondition.BMS_CONNECTED,0,160,0,30000,20,90,0,100);
        CoreTests.check(bmsOnly.matches(new WidgetCondition.Measurements(1,1,50,50,false,Float.NaN,Float.NaN,Float.NaN,Float.NaN,true))&&!bmsOnly.matches(new WidgetCondition.Measurements(1,1,50,50,false,Float.NaN,Float.NaN,Float.NaN,Float.NaN,false))&&bmsOnly.uses(WidgetCondition.BMS_CONNECTED),"the BMS-connected condition");
        WidgetSettings shown=WidgetSettings.DEFAULT.with(WidgetSettings.BMS,true);
        SidebarLayout.Stack stack=SidebarLayout.arrange(shown,WidgetSettings.BMS,0,new SidebarLayout.Sizes(100,190,100,190,190,190,120,190,2*BmsCard.ROW_HEIGHT),false);
        CoreTests.check(stack.bms()!=null&&stack.bms().bottom()==SidebarLayout.BOTTOM&&stack.bms().height()==2*BmsCard.ROW_HEIGHT&&stack.bms().width()==190&&stack.of(WidgetSettings.BMS)==stack.bms(),"the BMS card takes the height its rows need");
    }
    private static byte[] answer(int fc,int serial,byte[] content){
        byte[] f=new byte[13+content.length+2];f[0]=0x5a;f[1]=(byte)0xa5;f[2]=0;f[3]=0x40;f[4]=(byte)fc;f[5]=(byte)serial;f[6]=(byte)(serial>>8);f[7]=1;f[9]=1;f[11]=(byte)content.length;f[12]=(byte)(content.length>>8);
        System.arraycopy(content,0,f,13,content.length);int sum=DlBmsProtocol.checksum(f,f.length-2);f[f.length-2]=(byte)sum;f[f.length-1]=(byte)(sum>>8);return f;
    }
    private static void put16(byte[] b,int i,int v){b[i]=(byte)v;b[i+1]=(byte)(v>>8);}
    private static void put32(byte[] b,int i,long v){b[i]=(byte)v;b[i+1]=(byte)(v>>8);b[i+2]=(byte)(v>>16);b[i+3]=(byte)(v>>24);}
    private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(java.util.Locale.ROOT,"%02X ",v));return b.toString().trim();}
}
