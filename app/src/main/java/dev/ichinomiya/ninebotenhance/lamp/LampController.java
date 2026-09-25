package dev.ichinomiya.ninebotenhance.lamp;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import dev.ichinomiya.ninebotenhance.core.*;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The module's own GATT link to one lamp controller (a TX hoist, a 摩灯客 headlight ESC or canopy controller, or an SG lift; see
 * {@link LampKind}), running in the module process on the module's own Bluetooth permissions; it
 * never touches the vehicle's link or Ninebot's Bluetooth stack. The link lives only while something holds it: a cast session
 * (every dashboard snapshot renews the hold), a visible Ninebot screen (its status poll renews it) or the lamp screen. When the
 * last hold lapses the GATT is closed and the controller goes quiet, so a lamp Ninebot is not using is free for anything else
 * and the module has nothing ticking in the background.
 * <p>
 * TX: only 0x11 to authenticate and 0x12 to drive to a height are written. The vendor application also echoes a 0x13 travel
 * configuration after each move, but its control-mode bit has no trustworthy source and sending that frame was tried once and
 * changed nothing here, so it is left alone. Height, speed and travel limits come from the device's own 0x15 frames; this one
 * never streams them while the motor runs and only answers the handshake with one, so the handshake doubles as the height poll.
 * ESC: A9 as the liveness probe, then A3 direction 3 for an absolute height; the device reports nothing back, so the last target
 * is remembered across runs. Canopy: the bare CC position command and the 0x1D query, whose answer and the pushed 0x20 frames
 * carry the device's own position. SG: the AF session opener, then jog heartbeats every 100 ms for the configured time followed
 * by two stop frames; it has no position, so the card only says it is connected.
 */
public final class LampController {
    /** One hold from a dashboard snapshot or Ninebot's status poll; longer than the one second poll so a late one does not drop the link. */
    public static final long HOLD_MS=6000;
    /** The lamp screen renews its hold every few seconds while visible and lets go when it leaves. */
    public static final long SCREEN_HOLD_MS=15000,SCREEN_RENEW_MS=5000;
    /** Who holds the link: each source keeps its own deadline and the link stays while any of them is in the future. */
    public static final int HOLD_SESSION=0,HOLD_FOREGROUND=1,HOLD_SCREEN=2;
    private static final long AUTH_TIMEOUT_MS=8000,RETRY_MS=5000,TICK_MS=1000;
    /** A write whose completion callback never arrives must not wedge the queue. */
    private static final long WRITE_TIMEOUT_MS=2500;
    /** Ceiling of the growing pause between handshakes the device never answers. */
    private static final long AUTH_BACKOFF_MAX_MS=60000;
    /** Rapid volume presses accumulate into one target instead of one write each. */
    private static final long STEP_INTERVAL_MS=260,TARGET_MEMORY_MS=4000;
    private static LampController instance;
    public static synchronized LampController get(Context context){
        if(instance==null)instance=new LampController(context.getApplicationContext());
        return instance;
    }
    private final Context context;private final Handler worker,main;
    private final CopyOnWriteArrayList<Consumer<LampState>> watchers=new CopyOnWriteArrayList<>();
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    private volatile LampSettings settings;
    private volatile LampState state=LampState.NONE;
    private BluetoothGatt gatt;private BluetoothGattCharacteristic writeCharacteristic;
    private boolean writing,ticking,authenticated,idle=true;private int authFailures;
    private final long[] holds=new long[3];
    private long retryAt,lastWriteAt,desiredAt,pollUntil;private int desired=-1;private boolean stepScheduled;
    /** ESC only: the last height sent, remembered across runs because the device never reports one; -1 until the first move. */
    private int tracked=-1;
    /** SG only: the direction being jogged (0 when still) and when the current burst ends. */
    private int jogDirection;private long jogUntil;
    /** Control mode bit of the 0x13 echo; the only field of that frame the device never tells us. */
    /** Height polls: fast while the hoist could still be running after a move, slow while a screen or session is watching. */
    private static final long POLL_DELAY_MS=1200,POLL_WINDOW_MS=20000,POLL_IDLE_MS=15000;
    private LampController(Context context){
        this.context=context;main=new Handler(Looper.getMainLooper());
        HandlerThread thread=new HandlerThread("Ninebot-Lamp",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();
        worker=new Handler(thread.getLooper());
        settings=read();
        state=settings.bound()?LampState.of(LampState.IDLE,""):LampState.NONE;
        worker.post(this::tick);
    }
    // ---------------------------------------------------------------- settings
    private LampSettings read(){
        try{
            android.content.SharedPreferences p=context.getSharedPreferences(Protocol.MODULE+".lamp",Context.MODE_PRIVATE);
            tracked=p.getInt("position",-1);
            return new LampSettings(p.getString("mac",""),p.getString("password",""),
                    p.getInt("speed",LampSettings.DEFAULT_SPEED),p.getInt("steps",LampSettings.DEFAULT_STEPS),
                    p.getBoolean("reversed",false),p.getBoolean("volume_control",true),
                    LampKind.of(p.getInt("kind",LampKind.TX.id)),p.getInt("jog_ms",LampSettings.DEFAULT_JOG_MS));
        }catch(RuntimeException e){return LampSettings.NONE;}
    }
    private void remember(int position){
        tracked=position;
        try{context.getSharedPreferences(Protocol.MODULE+".lamp",Context.MODE_PRIVATE).edit().putInt("position",position).apply();}catch(RuntimeException ignored){}
    }
    public LampSettings settings(){return settings;}
    /** Saving a different device, kind or password drops the current link so the next hold authenticates afresh. */
    public void save(LampSettings value){
        LampSettings previous=settings;settings=value;
        boolean identity=!previous.mac().equals(value.mac())||!previous.password().equals(value.password())||previous.kind()!=value.kind();
        try{
            android.content.SharedPreferences.Editor editor=context.getSharedPreferences(Protocol.MODULE+".lamp",Context.MODE_PRIVATE).edit()
                    .putString("mac",value.mac()).putString("password",value.password())
                    .putInt("speed",value.speed()).putInt("steps",value.steps())
                    .putBoolean("reversed",value.reversed()).putBoolean("volume_control",value.volumeControl())
                    .putInt("kind",value.kind().id).putInt("jog_ms",value.jogMs());
            if(identity)editor.remove("position");
            editor.apply();
        }catch(RuntimeException ignored){}
        worker.post(()->{if(identity){close("绑定已更改");retryAt=0;tracked=-1;}tick();});
    }
    public LampState state(){return state;}
    public void watch(Consumer<LampState> watcher){watchers.add(watcher);main.post(()->watcher.accept(state));}
    public void unwatch(Consumer<LampState> watcher){watchers.remove(watcher);}
    // ---------------------------------------------------------------- lifetime
    /** Keep the link open for this long on behalf of one source; renewing before it lapses keeps one continuous connection. */
    public void hold(int source,long millis){
        long until=SystemClock.elapsedRealtime()+Math.max(0,millis);
        worker.post(()->{if(until>holds[source])holds[source]=until;tick();});
    }
    /** One source lets go now; the link closes on the next tick unless another source still holds it. */
    public void release(int source){worker.post(()->{holds[source]=0;tick();});}
    private long heldUntil(){long until=0;for(long value:holds)until=Math.max(until,value);return until;}
    private boolean held(){return SystemClock.elapsedRealtime()<heldUntil();}
    private static void log(String message){android.util.Log.i(Protocol.TAG,"LAMP "+message);}
    public boolean permitted(){
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }
    public boolean scanPermitted(){
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
    }
    private BluetoothAdapter adapter(){
        try{BluetoothManager manager=context.getSystemService(BluetoothManager.class);return manager==null?null:manager.getAdapter();}
        catch(RuntimeException e){return null;}
    }
    /**
     * The link exists exactly while someone holds it. A held lamp connects directly and is retried every few seconds; once the
     * last hold lapses the GATT is closed, the state drops to idle and the tick stops, so nothing runs until the next hold. A new
     * hold after an idle spell starts afresh instead of waiting out a retry pause left over from before.
     */
    private void tick(){
        long now=SystemClock.elapsedRealtime();
        LampSettings current=settings;
        if(!current.bound()){drop();publish(LampState.NONE);stopTicking();return;}
        if(now>=heldUntil()){
            if(!idle){idle=true;log("released");}
            drop();publish(LampState.of(LampState.IDLE,""));stopTicking();return;
        }
        if(idle){idle=false;retryAt=0;}
        if(!permitted()){drop();publish(LampState.of(LampState.DENIED,""));schedule();return;}
        if(gatt==null&&now>=retryAt)open(current);
        schedule();
    }
    private void stopTicking(){ticking=false;worker.removeCallbacks(tickRunnable);}
    private void schedule(){worker.removeCallbacks(tickRunnable);ticking=true;worker.postDelayed(tickRunnable,TICK_MS);}
    private final Runnable tickRunnable=new Runnable(){@Override public void run(){ticking=false;tick();}};
    private void open(LampSettings current){
        BluetoothAdapter adapter=adapter();
        if(adapter==null||!adapter.isEnabled()){publish(LampState.of(LampState.FAILED,"蓝牙未开启"));retryAt=SystemClock.elapsedRealtime()+RETRY_MS;return;}
        BluetoothDevice device;
        try{device=adapter.getRemoteDevice(current.mac());}
        catch(RuntimeException e){publish(LampState.of(LampState.FAILED,"地址无效"));return;}
        queue.clear();writing=false;authenticated=false;writeCharacteristic=null;
        publish(state.withPhase(LampState.CONNECTING,""));
        try{gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);}
        catch(RuntimeException e){gatt=null;publish(LampState.of(LampState.FAILED,error(e)));}
        retryAt=SystemClock.elapsedRealtime()+RETRY_MS;
        log("open "+current.mac());
    }
    /**
     * A wrong password looks like silence: this device answers an accepted handshake and ignores a rejected one entirely, so a
     * handshake with no answer is reported as such and retried ever more slowly instead of hammering the device forever.
     */
    private final Runnable authTimeout=new Runnable(){@Override public void run(){
        if(gatt==null||authenticated)return;
        authFailures++;close(settings.kind()==LampKind.TX?"密码无应答":"握手无应答");
        retryAt=SystemClock.elapsedRealtime()+Math.min(AUTH_BACKOFF_MAX_MS,RETRY_MS*authFailures);
    }};
    /** Tear the GATT down without saying anything about it; the callers decide what state that leaves. */
    private void drop(){
        worker.removeCallbacks(authTimeout);worker.removeCallbacks(statusPoll);worker.removeCallbacks(writeWatchdog);worker.removeCallbacks(jogPulse);
        BluetoothGatt open=gatt;gatt=null;writeCharacteristic=null;writing=false;authenticated=false;queue.clear();desired=-1;jogDirection=0;
        if(open!=null)try{open.disconnect();open.close();}catch(RuntimeException ignored){}
    }
    private void close(String detail){
        drop();
        if(!detail.isEmpty())publish(LampState.of(LampState.FAILED,detail));
        else if(state.phase()!=LampState.UNBOUND&&state.phase()!=LampState.REJECTED&&state.phase()!=LampState.IDLE)publish(LampState.of(LampState.CONNECTING,""));
    }
    private void publish(LampState next){
        if(next.equals(state))return;
        state=next;log("state "+next.describe());
        for(Consumer<LampState> watcher:watchers)main.post(()->watcher.accept(next));
    }
    private static String error(Throwable e){return e.getClass().getSimpleName();}
    // ---------------------------------------------------------------- writing
    private void enqueue(byte[] frame){queue.addLast(frame);if(queue.size()>8)queue.removeFirst();drain();}
    private void drain(){
        if(writing||gatt==null||writeCharacteristic==null||queue.isEmpty())return;
        byte[] frame=queue.pollFirst();writing=true;lastWriteAt=SystemClock.elapsedRealtime();
        // These modules often expose the write characteristic without a response; asking for one then fails every write.
        int type=(writeCharacteristic.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0
                ?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        try{
            int status=gatt.writeCharacteristic(writeCharacteristic,frame,type);
            log("write "+hex(frame)+" type="+type+" status="+status);
            if(status!=BluetoothStatusCodes.SUCCESS){writing=false;publish(state.withPhase(state.phase(),"写入被拒绝 "+status));return;}
            worker.removeCallbacks(writeWatchdog);worker.postDelayed(writeWatchdog,WRITE_TIMEOUT_MS);
        }catch(RuntimeException e){writing=false;close(error(e));}
    }
    private final Runnable writeWatchdog=new Runnable(){@Override public void run(){
        if(!writing)return;
        log("write callback missing, queue="+queue.size());writing=false;drain();
    }};
    /** Whether the advertisement lists the service, which is all the 摩灯客 controllers identify themselves by. */
    private static boolean advertises(ScanRecord record,String service){
        if(record==null)return false;
        List<android.os.ParcelUuid> uuids=record.getServiceUuids();if(uuids==null)return false;
        for(android.os.ParcelUuid uuid:uuids)if(uuid.getUuid().toString().equalsIgnoreCase(service))return true;
        return false;
    }
    private static String hex(byte[] data){
        StringBuilder text=new StringBuilder();
        for(byte value:data)text.append(String.format(Locale.ROOT,"%02X",value));
        return text.toString();
    }
    /** Drive a positional device to an absolute height, kept inside the travel limits the device reported. */
    public void moveTo(int position){
        worker.post(()->{
            LampSettings current=settings;LampState now=state;
            if(!now.ready()||!current.kind().positional)return;
            int target=TxLampProtocol.clampPosition(position,now.lowLimit(),now.highLimit());
            desired=target;desiredAt=SystemClock.elapsedRealtime();
            move(target);
        });
    }
    /**
     * One volume key press. Returns whether the lamp took it: the caller restores the phone volume only when it did, so a press
     * either changes the volume or moves the lamp, never both. Presses land on the running target rather than on the last report,
     * so holding the key walks the hoist one notch at a time instead of repeating one step. {@code up} always brightens the light;
     * with the binding reversed that is the low end of the device travel, so the raw target moves the other way. A timed lift
     * instead runs for the configured time in that direction; a press during the run extends it.
     */
    public boolean stepFromVolume(boolean up){
        LampSettings current=settings;LampState now=state;
        if(!current.volumeControl()||!current.bound()||!now.ready())return false;
        boolean deviceUp=up^current.reversed();
        if(!current.kind().positional){worker.post(()->jog(deviceUp));return true;}
        // The ESC never reports a height: before the first move the middle of the travel is taken as the starting point.
        if(!now.knownPosition()&&current.kind()!=LampKind.ESC)return false;
        worker.post(()->{
            LampState live=state;if(!live.ready())return;
            long at=SystemClock.elapsedRealtime();
            int lo=Math.max(TxLampProtocol.MIN_POSITION,live.lowLimit()),hi=Math.min(current.kind().maxPosition,live.highLimit());
            if(lo>hi){lo=TxLampProtocol.MIN_POSITION;hi=current.kind().maxPosition;}
            int from=desired>=0&&at-desiredAt<=TARGET_MEMORY_MS?desired:live.knownPosition()?live.position():(lo+hi)/2;
            int delta=current.stepUnits(lo,hi);
            hi=current.top(lo,hi);
            desired=Math.max(lo,Math.min(hi,from+(deviceUp?delta:-delta)));
            desiredAt=at;
            long wait=Math.max(0,STEP_INTERVAL_MS-(at-lastWriteAt));
            if(wait==0){flushStep();}
            else if(!stepScheduled){stepScheduled=true;worker.postDelayed(()->{stepScheduled=false;flushStep();},wait);}
        });
        return true;
    }
    private void flushStep(){
        LampState now=state;
        if(desired<0||!now.ready()){log("step dropped desired="+desired+" phase="+now.phase());return;}
        log("step to "+desired+"% speed="+settings.protocolSpeed());
        move(desired);
    }
    /**
     * The height command of a positional device. TX: 0x12 alone; the vendor application also sends a 0x13 travel configuration
     * but nothing is invented here. ESC: A3 with the absolute target, which is then remembered as the position since the device
     * never reports one. Canopy: the bare CC command, followed by a query so the card shows where the device says it is.
     */
    private void move(int target){
        LampSettings current=settings;
        switch(current.kind()){
            case TX->{enqueue(TxLampProtocol.moveTo(target,current.protocolSpeed()));poll(POLL_WINDOW_MS);}
            case ESC->{enqueue(EscLampProtocol.moveTo(current.password(),target));remember(target);publish(state.withPosition(target,-1,EscLampProtocol.MIN_POSITION,EscLampProtocol.MAX_POSITION));}
            case CANOPY->{enqueue(CanopyLampProtocol.moveTo(target));poll(POLL_WINDOW_MS);}
            default->{}
        }
    }
    /** One press of a timed lift: jog heartbeats in that direction until the burst ends, then stop. A press mid-burst extends it. */
    private void jog(boolean deviceUp){
        LampSettings current=settings;
        if(gatt==null||!authenticated||current.kind().positional)return;
        int direction=deviceUp?SgLampProtocol.DIR_ONE:SgLampProtocol.DIR_TWO;
        if(jogDirection!=0&&jogDirection!=direction)enqueue(SgLampProtocol.stop());
        jogDirection=direction;jogUntil=SystemClock.elapsedRealtime()+current.jogMs();
        log("jog "+(deviceUp?"up":"down")+" "+current.jogMs()+" ms pwm="+current.pwm());
        worker.removeCallbacks(jogPulse);jogPulse.run();
    }
    private final Runnable jogPulse=new Runnable(){@Override public void run(){
        if(gatt==null||!authenticated||jogDirection==0){jogDirection=0;return;}
        if(SystemClock.elapsedRealtime()>=jogUntil){
            // The device has no receipt for a stop, so it is sent twice as the vendor notes advise.
            jogDirection=0;enqueue(SgLampProtocol.stop());enqueue(SgLampProtocol.stop());log("jog stop");return;
        }
        enqueue(SgLampProtocol.jog(jogDirection,settings.pwm()));
        worker.postDelayed(this,SgLampProtocol.HEARTBEAT_MS);
    }};
    /**
     * Ask the device where it is. The TX hoist answers the handshake with its 0x15 position frame and streams nothing on its own
     * while the motor runs, so re-sending the handshake is the only way to read a height back; the canopy answers the 0x1D query
     * with its position. Neither changes anything on the device. The ESC and SG have nothing to ask.
     */
    private void poll(long window){
        if(query(settings)==null)return;
        pollUntil=Math.max(pollUntil,SystemClock.elapsedRealtime()+window);
        worker.removeCallbacks(statusPoll);worker.postDelayed(statusPoll,POLL_DELAY_MS);
    }
    private static byte[] query(LampSettings current){
        return switch(current.kind()){
            case TX->TxLampProtocol.auth(current.password());
            case CANOPY->CanopyLampProtocol.queryConfig();
            default->null;
        };
    }
    private final Runnable statusPoll=new Runnable(){@Override public void run(){
        if(gatt==null||!authenticated)return;
        byte[] frame=query(settings);if(frame==null)return;
        enqueue(frame);
        long now=SystemClock.elapsedRealtime();boolean moving=now<pollUntil,watched=now<heldUntil();
        // Re-arming is driven by these two clocks alone; the answer to a poll must never schedule the next one.
        if(moving||watched)worker.postDelayed(this,moving?POLL_DELAY_MS:POLL_IDLE_MS);
    }};
    // ---------------------------------------------------------------- GATT
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){
            worker.post(()->{
                if(g!=gatt)return;
                if(newState==BluetoothProfile.STATE_CONNECTED){
                    publish(state.withPhase(LampState.AUTHENTICATING,""));
                    worker.removeCallbacks(authTimeout);worker.postDelayed(authTimeout,AUTH_TIMEOUT_MS);
                    // Android caches a device's attribute handles; a stale cache accepts writes that reach nothing and never
                    // delivers a notification. The hidden refresh drops it so discovery reads the handles from the device.
                    try{BluetoothGatt.class.getMethod("refresh").invoke(g);log("cache refreshed");}
                    catch(ReflectiveOperationException|RuntimeException e){log("cache refresh unavailable "+error(e));}
                    try{g.discoverServices();}catch(RuntimeException e){close(error(e));}
                }else if(newState==BluetoothProfile.STATE_DISCONNECTED){log("disconnected status="+status);close(status==0?"":"连接断开 "+status);}
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            worker.post(()->{
                if(g!=gatt)return;
                LampKind kind=settings.kind();BluetoothGattService service=null;
                try{service=g.getService(UUID.fromString(kind.service));}catch(RuntimeException ignored){}
                if(service==null){close("未找到灯控服务");return;}
                writeCharacteristic=service.getCharacteristic(UUID.fromString(kind.write));
                BluetoothGattCharacteristic notify=kind.singleCharacteristic()?writeCharacteristic:service.getCharacteristic(UUID.fromString(kind.notify));
                if(writeCharacteristic==null||notify==null){close("灯控特征缺失");return;}
                log("characteristics write=0x"+Integer.toHexString(writeCharacteristic.getProperties())+" notify=0x"+Integer.toHexString(notify.getProperties()));
                try{
                    boolean enabled=g.setCharacteristicNotification(notify,true);
                    BluetoothGattDescriptor cccd=notify.getDescriptor(UUID.fromString(TxLampProtocol.CCCD));
                    if(cccd==null){close("通知描述符缺失");return;}
                    int subscribed=g.writeDescriptor(cccd,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    log("notify enabled="+enabled+" cccd write="+subscribed);
                }catch(RuntimeException e){close(error(e));}
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status){
            worker.post(()->{
                if(g!=gatt)return;
                log("cccd written status="+status);
                if(status!=BluetoothGatt.GATT_SUCCESS){close("通知订阅失败 "+status);return;}
                handshake();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic ch,int status){
            worker.post(()->{if(g!=gatt)return;worker.removeCallbacks(writeWatchdog);writing=false;log("written status="+status);drain();});
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic ch,byte[] value){
            worker.post(()->{if(g==gatt)received(value);});
        }
    };
    /**
     * The first exchange after subscribing. TX: the password handshake, which the device only answers when it is right. ESC: the
     * firmware query, answered by B9; the password is only judged by the receipt of the first move. Canopy: the configuration
     * query, answered with the position. SG: the session opener, answered with an AF status frame.
     */
    private void handshake(){
        LampSettings current=settings;
        switch(current.kind()){
            case TX->enqueue(TxLampProtocol.auth(current.password()));
            case ESC->enqueue(EscLampProtocol.query());
            case CANOPY->enqueue(CanopyLampProtocol.queryConfig());
            case SG->enqueue(SgLampProtocol.init());
        }
    }
    /** The device answered the handshake: the link is usable. */
    private void ready(){
        boolean first=!authenticated;
        authenticated=true;authFailures=0;worker.removeCallbacks(authTimeout);retryAt=0;
        publish(state.withPhase(LampState.READY,""));
        if(!first)return;
        if(settings.kind()==LampKind.ESC&&tracked>=0)publish(state.withPosition(tracked,-1,EscLampProtocol.MIN_POSITION,EscLampProtocol.MAX_POSITION));
        if(held())poll(0);
    }
    private void received(byte[] value){
        log("recv "+hex(value));
        switch(settings.kind()){
            case TX->receivedTx(value);
            case ESC->{
                EscLampProtocol.Response response=EscLampProtocol.parse(value);
                if(response==null)return;
                if(response.type()==EscLampProtocol.TYPE_INFO)ready();
                else if(EscLampProtocol.receipt(response,EscLampProtocol.TYPE_MOVE)&&!EscLampProtocol.accepted(response,EscLampProtocol.TYPE_MOVE)){
                    authenticated=false;log("password rejected");publish(LampState.of(LampState.REJECTED,""));close("");
                }
            }
            case CANOPY->{
                int position=CanopyLampProtocol.position(value);
                if(position<0)return;
                ready();publish(state.withPosition(position,-1,CanopyLampProtocol.MIN_POSITION,CanopyLampProtocol.MAX_POSITION));
            }
            case SG->{
                if(SgLampProtocol.connected(value))ready();
                else if(SgLampProtocol.alarm(value))log("motor stalled");
                else{int motor=SgLampProtocol.motor(value);if(motor>=0)log("motor "+(motor==0?"stopped":"running "+motor));}
            }
        }
    }
    private void receivedTx(byte[] value){
        int command=TxLampProtocol.command(value);
        if(command==TxLampProtocol.CMD_AUTH||command==TxLampProtocol.CMD_PASSWORD){
            if(TxLampProtocol.accepted(value))ready();
            else{authenticated=false;log("password rejected");publish(LampState.of(LampState.REJECTED,""));close("");}
            return;
        }
        TxLampProtocol.Report report=TxLampProtocol.parse(value);
        if(report==null||!authenticated)return;
        if(report.state())publish(state.withPosition(report.position(),report.speed(),report.low(),report.high()));
    }
    // ---------------------------------------------------------------- scanning
    /** One advertiser; {@code matched} marks the ones that look like the kind of controller being looked for. */
    public record Found(String mac,String name,int rssi,boolean matched){}
    /** Everything one scan saw, plus the framework's failure code (0 when the scan actually ran). */
    public record Scan(List<Found> devices,int failure){}
    public static final int SCAN_UNAVAILABLE=-1;
    /**
     * Collect nearby BLE devices for the given time; the result lands on the main thread. Every advertiser is returned with the
     * ones matching the kind flagged and listed first, so a controller with an unexpected name can still be picked by address. Low
     * latency mode with aggressive matching: the default low power duty cycle leaves the radio off most of the time and can miss
     * a slow advertiser completely within a few seconds.
     */
    public void scan(LampKind kind,long millis,Consumer<Scan> done){
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
                String name=null;
                try{name=result.getDevice().getName();}catch(RuntimeException ignored){}
                if(name==null||name.isEmpty()){ScanRecord advertised=result.getScanRecord();name=advertised==null?null:advertised.getDeviceName();}
                String mac=result.getDevice().getAddress();if(mac==null)return;
                String label=name==null?"":name;
                boolean matched=kind.matches(label,advertises(result.getScanRecord(),kind.service));
                synchronized(found){
                    // A later advertisement without a name must not overwrite one that carried it.
                    Found previous=found.get(mac);
                    if(previous!=null&&label.isEmpty()&&!previous.name().isEmpty())return;
                    found.put(mac,new Found(mac,label,result.getRssi(),matched));
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
