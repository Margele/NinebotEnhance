package dev.ichinomiya.ninebotenhance.bms;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.SparseArray;
import dev.ichinomiya.ninebotenhance.core.*;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The module's own GATT link to one DL BMS protection board, in the module process on the module's own Bluetooth permissions.
 * Every connection starts with the FC00 key exchange (a fresh secp256k1 pair each time, the shared secret computed locally), after
 * which FC17 is polled at the configured interval while something holds the link (a cast session, a visible Ninebot screen or
 * the BMS screen); once the last hold lapses the link is closed and nothing runs until the next hold. The board drops idle
 * links by itself, so a dropped link is simply reopened and the handshake repeated. Nothing is ever written to the board but
 * FC00 and FC17.
 */
public final class BmsController {
    public static final long HOLD_MS=6000,SCREEN_HOLD_MS=15000,SCREEN_RENEW_MS=5000;
    /** Who holds the link; the same three sources as the lamp. */
    public static final int HOLD_SESSION=0,HOLD_FOREGROUND=1,HOLD_SCREEN=2;
    private static final long HANDSHAKE_TIMEOUT_MS=8000,RETRY_MS=5000,TICK_MS=1000;
    private static final long WRITE_TIMEOUT_MS=2500,CHUNK_GAP_MS=30,BACKOFF_MAX_MS=60000;
    private static final int DEFAULT_MTU=23,REQUEST_MTU=247;
    private static BmsController instance;
    public static synchronized BmsController get(Context context){
        if(instance==null)instance=new BmsController(context.getApplicationContext());
        return instance;
    }
    private final Context context;private final Handler worker,main;
    private final CopyOnWriteArrayList<Consumer<BmsState>> watchers=new CopyOnWriteArrayList<>();
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    private final SecureRandom random=new SecureRandom();
    private volatile BmsSettings settings;
    private volatile BmsState state=BmsState.NONE;
    private BluetoothGatt gatt;private BluetoothGattCharacteristic writeCharacteristic;
    private boolean writing,ticking,idle=true;private int failures,mtu=DEFAULT_MTU,serial;
    private final long[] holds=new long[3];
    private long retryAt,lastDataAt;private int missedPolls;
    private byte[] privateKey,key,iv;private byte[] inbound=new byte[512];private int inboundLength;
    private BmsController(Context context){
        this.context=context;main=new Handler(Looper.getMainLooper());
        HandlerThread thread=new HandlerThread("Ninebot-Bms",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();
        worker=new Handler(thread.getLooper());
        settings=read();
        state=settings.bound()?BmsState.of(BmsState.IDLE,""):BmsState.NONE;
        worker.post(this::tick);
    }
    // ---------------------------------------------------------------- settings
    private BmsSettings read(){
        try{
            android.content.SharedPreferences p=context.getSharedPreferences(Protocol.MODULE+".bms",Context.MODE_PRIVATE);
            return new BmsSettings(p.getString("mac",""),p.getInt("poll_ms",BmsSettings.DEFAULT_POLL_MS));
        }catch(RuntimeException e){return BmsSettings.NONE;}
    }
    public BmsSettings settings(){return settings;}
    public void save(BmsSettings value){
        BmsSettings previous=settings;settings=value;
        try{context.getSharedPreferences(Protocol.MODULE+".bms",Context.MODE_PRIVATE).edit().putString("mac",value.mac()).putInt("poll_ms",value.pollMs()).apply();}
        catch(RuntimeException ignored){}
        boolean identity=!previous.mac().equals(value.mac());
        worker.post(()->{if(identity){close("绑定已更改");retryAt=0;failures=0;}tick();});
    }
    public BmsState state(){return state;}
    public void watch(Consumer<BmsState> watcher){watchers.add(watcher);main.post(()->watcher.accept(state));}
    public void unwatch(Consumer<BmsState> watcher){watchers.remove(watcher);}
    // ---------------------------------------------------------------- lifetime
    public void hold(int source,long millis){
        long until=SystemClock.elapsedRealtime()+Math.max(0,millis);
        worker.post(()->{if(until>holds[source])holds[source]=until;tick();});
    }
    public void release(int source){worker.post(()->{holds[source]=0;tick();});}
    private long heldUntil(){long until=0;for(long value:holds)until=Math.max(until,value);return until;}
    private boolean held(){return SystemClock.elapsedRealtime()<heldUntil();}
    private static void log(String message){android.util.Log.i(Protocol.TAG,"BMS "+message);}
    public boolean permitted(){return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    public boolean scanPermitted(){return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    private BluetoothAdapter adapter(){
        try{BluetoothManager manager=context.getSystemService(BluetoothManager.class);return manager==null?null:manager.getAdapter();}
        catch(RuntimeException e){return null;}
    }
    /** The link exists exactly while someone holds it; the same lifetime as LampController.tick. */
    private void tick(){
        long now=SystemClock.elapsedRealtime();
        BmsSettings current=settings;
        if(!current.bound()){drop();publish(BmsState.NONE);stopTicking();return;}
        if(now>=heldUntil()){
            if(!idle){idle=true;log("released");}
            drop();publish(BmsState.of(BmsState.IDLE,""));stopTicking();return;
        }
        if(idle){idle=false;retryAt=0;}
        if(!permitted()){drop();publish(BmsState.of(BmsState.DENIED,""));schedule();return;}
        if(gatt==null&&now>=retryAt)open(current);
        schedule();
    }
    private void stopTicking(){ticking=false;worker.removeCallbacks(tickRunnable);}
    private void schedule(){worker.removeCallbacks(tickRunnable);ticking=true;worker.postDelayed(tickRunnable,TICK_MS);}
    private final Runnable tickRunnable=new Runnable(){@Override public void run(){ticking=false;tick();}};
    private void open(BmsSettings current){
        BluetoothAdapter adapter=adapter();
        if(adapter==null||!adapter.isEnabled()){publish(BmsState.of(BmsState.FAILED,"蓝牙未开启"));retryAt=SystemClock.elapsedRealtime()+RETRY_MS;return;}
        BluetoothDevice device;
        try{device=adapter.getRemoteDevice(current.mac());}
        catch(RuntimeException e){publish(BmsState.of(BmsState.FAILED,"地址无效"));return;}
        queue.clear();writing=false;writeCharacteristic=null;key=iv=privateKey=null;inboundLength=0;mtu=DEFAULT_MTU;missedPolls=0;
        publish(state.withPhase(BmsState.CONNECTING,""));
        try{gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);}
        catch(RuntimeException e){gatt=null;publish(BmsState.of(BmsState.FAILED,error(e)));}
        retryAt=SystemClock.elapsedRealtime()+RETRY_MS;
        log("open "+current.mac());
    }
    private final Runnable handshakeTimeout=new Runnable(){@Override public void run(){
        if(gatt==null||key!=null)return;
        failures++;close("握手无应答");
        retryAt=SystemClock.elapsedRealtime()+Math.min(BACKOFF_MAX_MS,RETRY_MS*failures);
    }};
    private void drop(){
        worker.removeCallbacks(handshakeTimeout);worker.removeCallbacks(pollRunnable);worker.removeCallbacks(writeWatchdog);worker.removeCallbacks(chunkRunnable);
        BluetoothGatt open=gatt;gatt=null;writeCharacteristic=null;writing=false;queue.clear();key=iv=privateKey=null;inboundLength=0;pending=null;
        if(open!=null)try{open.disconnect();open.close();}catch(RuntimeException ignored){}
    }
    private void close(String detail){
        drop();
        if(!detail.isEmpty())publish(BmsState.of(BmsState.FAILED,detail));
        else if(state.phase()!=BmsState.UNBOUND&&state.phase()!=BmsState.IDLE)publish(BmsState.of(BmsState.CONNECTING,""));
    }
    private void publish(BmsState next){
        if(next.equals(state))return;
        state=next;if(!next.ready()||!next.data().known())log("state "+next.describe());
        for(Consumer<BmsState> watcher:watchers)main.post(()->watcher.accept(next));
    }
    private static String error(Throwable e){return e.getClass().getSimpleName();}
    // ---------------------------------------------------------------- writing (frames are cut into MTU-3 chunks, written with response)
    private byte[] pending;private int pendingOffset;
    private void enqueue(byte[] frame){queue.addLast(frame);if(queue.size()>4)queue.removeFirst();drain();}
    private void drain(){
        if(writing||gatt==null||writeCharacteristic==null)return;
        if(pending==null){if(queue.isEmpty())return;pending=queue.pollFirst();pendingOffset=0;}
        int chunk=Math.min(Math.max(20,mtu-3),pending.length-pendingOffset);
        byte[] part=Arrays.copyOfRange(pending,pendingOffset,pendingOffset+chunk);
        writing=true;
        try{
            int status=gatt.writeCharacteristic(writeCharacteristic,part,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if(status!=BluetoothStatusCodes.SUCCESS){writing=false;pending=null;log("write rejected "+status);publish(state.withPhase(state.phase(),"写入被拒绝 "+status));return;}
            pendingOffset+=chunk;if(pendingOffset>=pending.length)pending=null;
            worker.removeCallbacks(writeWatchdog);worker.postDelayed(writeWatchdog,WRITE_TIMEOUT_MS);
        }catch(RuntimeException e){writing=false;pending=null;close(error(e));}
    }
    private final Runnable writeWatchdog=new Runnable(){@Override public void run(){if(!writing)return;log("write callback missing");writing=false;pending=null;drain();}};
    private final Runnable chunkRunnable=new Runnable(){@Override public void run(){drain();}};
    private static String hex(byte[] data,int length){StringBuilder text=new StringBuilder();for(int i=0;i<length;i++)text.append(String.format(Locale.ROOT,"%02X",data[i]));return text.toString();}
    private void send(int fc,byte[] content){
        byte[] frame=DlBmsProtocol.frame(fc,content,++serial&0xffff);
        if(fc!=DlBmsProtocol.FC_KEY){if(key==null)return;frame=DlBmsProtocol.encrypt(key,iv,frame);}
        enqueue(frame);
    }
    // ---------------------------------------------------------------- polling
    private final Runnable pollRunnable=new Runnable(){@Override public void run(){
        if(gatt==null||key==null)return;
        long now=SystemClock.elapsedRealtime();
        if(lastDataAt>0&&now-lastDataAt>settings.pollMs()*3L+2000){missedPolls++;log("no data for "+missedPolls+" polls");}
        if(missedPolls>=3){close("读取无应答");retryAt=0;return;}
        send(DlBmsProtocol.FC_DATA,new byte[0]);
        if(held())worker.postDelayed(this,settings.pollMs());
    }};
    // ---------------------------------------------------------------- GATT
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){
            worker.post(()->{
                if(g!=gatt)return;
                if(newState==BluetoothProfile.STATE_CONNECTED){
                    publish(state.withPhase(BmsState.HANDSHAKE,""));
                    worker.removeCallbacks(handshakeTimeout);worker.postDelayed(handshakeTimeout,HANDSHAKE_TIMEOUT_MS);
                    try{g.requestMtu(REQUEST_MTU);}catch(RuntimeException e){log("mtu request "+error(e));try{g.discoverServices();}catch(RuntimeException e2){close(error(e2));}}
                }else if(newState==BluetoothProfile.STATE_DISCONNECTED){log("disconnected status="+status);close(status==0?"":"连接断开 "+status);}
            });
        }
        @Override public void onMtuChanged(BluetoothGatt g,int size,int status){
            worker.post(()->{if(g!=gatt)return;if(status==BluetoothGatt.GATT_SUCCESS)mtu=size;log("mtu="+mtu+" status="+status);try{g.discoverServices();}catch(RuntimeException e){close(error(e));}});
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            worker.post(()->{
                if(g!=gatt)return;
                BluetoothGattService service=null;
                try{service=g.getService(UUID.fromString(DlBmsProtocol.SERVICE));}catch(RuntimeException ignored){}
                if(service==null){close("未找到 BMS 服务");return;}
                writeCharacteristic=service.getCharacteristic(UUID.fromString(DlBmsProtocol.CHAR_WRITE));
                BluetoothGattCharacteristic notify=service.getCharacteristic(UUID.fromString(DlBmsProtocol.CHAR_NOTIFY));
                if(writeCharacteristic==null||notify==null){close("BMS 特征缺失");return;}
                try{
                    g.setCharacteristicNotification(notify,true);
                    BluetoothGattDescriptor cccd=notify.getDescriptor(UUID.fromString(DlBmsProtocol.CCCD));
                    if(cccd==null){close("通知描述符缺失");return;}
                    g.writeDescriptor(cccd,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                }catch(RuntimeException e){close(error(e));}
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status){
            worker.post(()->{
                if(g!=gatt)return;
                if(status!=BluetoothGatt.GATT_SUCCESS){close("通知订阅失败 "+status);return;}
                privateKey=Secp256k1.privateKey(random);inboundLength=0;
                send(DlBmsProtocol.FC_KEY,Secp256k1.publicKey(privateKey));
                log("key exchange sent");
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic ch,int status){
            worker.post(()->{if(g!=gatt)return;worker.removeCallbacks(writeWatchdog);writing=false;if(status!=BluetoothGatt.GATT_SUCCESS)log("written status="+status);worker.postDelayed(chunkRunnable,CHUNK_GAP_MS);});
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic ch,byte[] value){
            worker.post(()->{if(g==gatt)received(value);});
        }
    };
    /** Notifications are pieces of one frame; plain frames are parsed as they complete, encrypted ones once whole blocks decrypt to a complete frame. */
    private void received(byte[] value){
        if(value==null||value.length==0)return;
        if(inboundLength+value.length>inbound.length){if(inboundLength+value.length>4096){inboundLength=0;log("inbound overflow");return;}inbound=Arrays.copyOf(inbound,Math.max(inbound.length*2,inboundLength+value.length));}
        System.arraycopy(value,0,inbound,inboundLength,value.length);inboundLength+=value.length;
        if(key==null){consume(inbound,inboundLength);return;}
        if(inboundLength%16!=0)return;
        byte[] plain;
        try{plain=DlBmsProtocol.decrypt(key,iv,Arrays.copyOf(inbound,inboundLength));}catch(RuntimeException e){inboundLength=0;log("decrypt "+error(e));return;}
        if(!DlBmsProtocol.responseHeader(plain,plain.length)){inboundLength=0;log("bad header "+hex(plain,Math.min(8,plain.length)));return;}
        int length=DlBmsProtocol.frameLength(plain,plain.length);
        if(length<0||length>plain.length)return;
        DlBmsProtocol.Response response=DlBmsProtocol.parse(plain,plain.length);
        inboundLength=0;
        if(response==null){log("checksum failed");return;}
        handle(response);
    }
    private void consume(byte[] buffer,int available){
        if(!DlBmsProtocol.responseHeader(buffer,available)){if(available>=4){inboundLength=0;log("bad plain header "+hex(buffer,Math.min(8,available)));}return;}
        int length=DlBmsProtocol.frameLength(buffer,available);
        if(length<0||length>available)return;
        DlBmsProtocol.Response response=DlBmsProtocol.parse(buffer,available);
        inboundLength=0;
        if(response==null){log("plain checksum failed");return;}
        handle(response);
    }
    private void handle(DlBmsProtocol.Response response){
        if(response.fc()==DlBmsProtocol.FC_KEY){
            if(privateKey==null||response.content().length!=64){log("unexpected key answer");return;}
            try{byte[] shared=Secp256k1.shared(privateKey,response.content());key=DlBmsProtocol.key(shared);iv=DlBmsProtocol.iv(shared);}
            catch(RuntimeException e){close("握手失败 "+error(e));return;}
            worker.removeCallbacks(handshakeTimeout);failures=0;retryAt=0;lastDataAt=0;missedPolls=0;
            publish(state.withPhase(BmsState.READY,""));log("handshake complete");
            worker.removeCallbacks(pollRunnable);worker.post(pollRunnable);
            return;
        }
        if(response.fc()==DlBmsProtocol.FC_DATA){
            long now=SystemClock.elapsedRealtime();
            BmsData data=DlBmsProtocol.parseData(response.content(),now);
            if(data==null){log("data too short "+response.content().length);return;}
            lastDataAt=now;missedPolls=0;publish(state.withData(data));
        }
    }
    /** Re-arm polling while someone holds the link; the snapshot renews the hold every second, so this keeps the cadence exact. */
    public void poll(){worker.post(()->{if(gatt!=null&&key!=null&&!worker.hasCallbacks(pollRunnable))worker.postDelayed(pollRunnable,settings.pollMs());});}
    // ---------------------------------------------------------------- scanning
    public record Found(String mac,String name,int rssi,boolean matched){}
    public record Scan(List<Found> devices,int failure){}
    public static final int SCAN_UNAVAILABLE=-1;
    public void scan(long millis,Consumer<Scan> done){
        BluetoothAdapter adapter=adapter();
        if(adapter==null||!adapter.isEnabled()||!scanPermitted()){main.post(()->done.accept(new Scan(List.of(),SCAN_UNAVAILABLE)));return;}
        BluetoothLeScanner scanner;
        try{scanner=adapter.getBluetoothLeScanner();}catch(RuntimeException e){scanner=null;}
        if(scanner==null){main.post(()->done.accept(new Scan(List.of(),SCAN_UNAVAILABLE)));return;}
        BluetoothLeScanner active=scanner;
        LinkedHashMap<String,Found> found=new LinkedHashMap<>();int[] failure={0};
        ScanCallback callback=new ScanCallback(){
            @Override public void onScanResult(int type,ScanResult result){keep(result);}
            @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult result:results)keep(result);}
            @Override public void onScanFailed(int code){synchronized(found){failure[0]=code;}}
            private void keep(ScanResult result){
                String name=null;ScanRecord advertised=result.getScanRecord();
                try{name=result.getDevice().getName();}catch(RuntimeException ignored){}
                if((name==null||name.isEmpty())&&advertised!=null)name=advertised.getDeviceName();
                String mac=result.getDevice().getAddress();if(mac==null)return;
                String label=name==null?"":name;boolean matched=DlBmsProtocol.deviceName(label);
                if(!matched&&advertised!=null){
                    SparseArray<byte[]> data=advertised.getManufacturerSpecificData();
                    if(data!=null)for(int i=0;i<data.size();i++)if(DlBmsProtocol.advertisement(data.keyAt(i),data.valueAt(i))){matched=true;break;}
                }
                synchronized(found){
                    Found previous=found.get(mac);
                    if(previous!=null&&label.isEmpty()&&!previous.name().isEmpty())return;
                    found.put(mac,new Found(mac,label,result.getRssi(),matched||(previous!=null&&previous.matched())));
                }
            }
        };
        ScanSettings scanSettings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build();
        try{active.startScan(null,scanSettings,callback);}
        catch(RuntimeException e){main.post(()->done.accept(new Scan(List.of(),SCAN_UNAVAILABLE)));return;}
        main.postDelayed(()->{
            try{active.stopScan(callback);}catch(RuntimeException ignored){}
            List<Found> out;int code;
            synchronized(found){
                ArrayList<Found> sorted=new ArrayList<>(found.values());
                sorted.sort((a,b)->a.matched()!=b.matched()?(a.matched()?-1:1):Integer.compare(b.rssi(),a.rssi()));
                out=List.copyOf(sorted);code=failure[0];
            }
            done.accept(new Scan(out,code));
        },Math.max(1000,millis));
    }
}
