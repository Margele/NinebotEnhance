package dev.ichinomiya.ninebotenhance.hook;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.EncoderOverride;
import dev.ichinomiya.ninebotenhance.diagnostics.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration/output observers plus the user's encoder overrides, installed only inside the Ninebot process. Overrides touch only the
 * vehicle session's video encoder: its MediaFormat before configure, the capture VideoConfig getters, the encoder loop interval, and a
 * live bitrate change through MediaCodec.setParameters.
 */
public final class EncodingHooks {
    private final HookHost module;
    private final FrameClient frames;
    private final EncodingDiagnostics log;
    private final Set<Executable> hooked=ConcurrentHashMap.newKeySet();
    private record Scope(EncodingDiagnostics.Session session) {}
    private final ThreadLocal<Scope> context=new ThreadLocal<>();
    private final WeakIdentityMap<EncodingDiagnostics.Session> created=new WeakIdentityMap<>();
    private final WeakIdentityMap<EncodingDiagnostics.Session> captureOwners=new WeakIdentityMap<>();
    private final Map<Object,Boolean> liveCodecs=Collections.synchronizedMap(new WeakHashMap<>());
    public EncodingHooks(HookHost module,FrameClient frames) { this.module=module;this.frames=frames;log=frames.encoding();frames.setEncoderOverrideApplier(this::apply); }

    public void install() {
        int installed=0;
        for(Method method:MediaCodec.class.getDeclaredMethods()) {
            String name=method.getName();
            Class<?>[] types=method.getParameterTypes();
            int flagIndex=types.length==4?(types[3]==int.class?3:types[2]==int.class?2:-1):-1;
            boolean configure=name.equals("configure") && types.length==4 && types[0]==MediaFormat.class && flagIndex>=0;
            boolean create=Modifier.isStatic(method.getModifiers()) && (name.equals("createEncoderByType")||name.equals("createByCodecName"));
            boolean callback=name.equals("setCallback") && types.length>0 && types[0]==MediaCodec.Callback.class;
            boolean output=name.equals("getOutputFormat") && method.getReturnType()==MediaFormat.class;
            boolean dequeue=name.equals("dequeueOutputBuffer") && types.length==2 && types[0]==MediaCodec.BufferInfo.class;
            boolean start=name.equals("start")&&types.length==0;
            boolean parameters=name.equals("setParameters")&&types.length==1&&types[0]==Bundle.class;
            boolean terminal=(name.equals("stop")||name.equals("reset")||name.equals("release"))&&types.length==0;
            if(!configure&&!create&&!callback&&!output&&!dequeue&&!start&&!terminal&&!parameters)continue;
            try { module.hook(method).intercept(chain->{
                Object codec=chain.getThisObject();
                EncodingDiagnostics.Session session=log.owner(codec);
                EncodingFormat requested=null;
                String parameterValues=null;
                if(create)session=captureSession();
                if(configure) {
                    try {
                        MediaFormat format=(MediaFormat)chain.getArg(0);
                        requested=read(format);
                        boolean videoEncoder=(((Integer)chain.getArg(flagIndex))&MediaCodec.CONFIGURE_FLAG_ENCODE)!=0 && requested.mime().startsWith("video/");
                        if(videoEncoder) {
                            EncodingDiagnostics.Session candidate=created.get(codec);
                            if(candidate==null)candidate=captureSession();
                            // Only the vehicle session's own encoder is overridden, right before Ninebot configures it.
                            if(candidate!=null&&override(format))requested=read(format);
                            if(candidate!=null&&log.associate(candidate,codec))session=candidate;
                        }
                        // A decoder or non-video reconfiguration must never appear as a cast encoder.
                        if(!videoEncoder) {
                            log.retire(session,codec);session=null;
                        }
                        if(session!=null)log.capture(session,"MediaCodec.configure.attempt",requested.describe());
                    } catch(Throwable ignored) { session=null; }
                }
                if(callback) {
                    try { Object value=chain.getArg(0);if(value!=null)inspectCallback(value.getClass()); }
                    catch(Throwable ignored) {}
                }
                if(parameters&&session!=null)try {
                    Bundle bundle=(Bundle)chain.getArg(0);List<String> values=new ArrayList<>();
                    if(bundle!=null)for(String key:new String[]{"video-bitrate","frame-rate","operating-rate","max-fps-to-encoder"}) {
                        String value=CaptureConfigReader.scalar(bundle.get(key),false);if(value!=null)values.add(key+"="+value);
                    }
                    if(!values.isEmpty())parameterValues=String.join(",",values);
                }catch(Throwable ignored) {}
                Object result;
                try { result=chain.proceed(); }
                catch(Throwable error) {
                    try { log.event(session,codec,name+" failed="+error.getClass().getSimpleName()); } catch(Throwable ignored) {}
                    throw error;
                }
                // Observer failures cannot change an original return value or throw into Ninebot.
                try {
                    if(create&&session!=null&&result!=null) {
                        synchronized(created) { if(created.size()<128)created.put(result,session); }
                    }
                    if(configure&&session!=null&&requested!=null) {
                        log.configured(session,codec,requested);
                        synchronized(liveCodecs) { if(liveCodecs.size()<16)liveCodecs.put(codec,Boolean.TRUE); }
                    }
                    if(parameterValues!=null)log.event(session,codec,"setParameters accepted raw={"+parameterValues+"}");
                    if(output&&result instanceof MediaFormat)log.format(session,codec,read((MediaFormat)result),true,"MediaCodec."+name);
                    if(start&&session!=null) {
                        log.event(session,codec,"start");
                        // Calling this after start is legal for sync and async codecs; no buffers are consumed.
                        log.format(session,codec,read(((MediaCodec)codec).getOutputFormat()),true,"MediaCodec.start/getOutputFormat");
                    }
                    if(dequeue&&result instanceof Integer&&(Integer)result>=0)buffer(session,codec,(MediaCodec.BufferInfo)chain.getArg(0));
                    if(dequeue&&session!=null&&Integer.valueOf(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED).equals(result))
                        log.format(session,codec,read(((MediaCodec)codec).getOutputFormat()),true,"INFO_OUTPUT_FORMAT_CHANGED");
                    if(terminal) { log.event(session,codec,name);log.retire(session,codec);liveCodecs.remove(codec); }
                } catch(Throwable ignored) {}
                return result;
            });installed++; }
            catch(Throwable error) { frames.report("ENCODING hook unavailable MediaCodec."+name+" "+error.getClass().getSimpleName()); }
        }
        frames.report("ENCODING observers MediaCodec="+installed+"; metadata only; waiting for vehicle session");
    }

    public void inspect(Class<?> type) {
        if(!HookPolicy.captureClass(type.getName()))return;
        installOverrides(type);
        boolean config=CaptureConfigReader.videoConfig(type);
        if(!type.isInterface()&&HookPolicy.captureConstructors(type.getName()))
            for(Constructor<?> constructor:type.getDeclaredConstructors())if(!HookPolicy.twin(constructor))installCapture(constructor,config);
        for(Method method:type.getDeclaredMethods()) {
            String name=method.getName().toLowerCase(Locale.ROOT);
            if(!Modifier.isAbstract(method.getModifiers())&&!method.isBridge()&&!HookPolicy.twin(method) && (name.startsWith("create")||name.startsWith("prepare")
                    ||name.startsWith("configure")||name.startsWith("init")||name.startsWith("start")||name.startsWith("setup")
                    ||name.matches("set.*(width|height|fps|framerate|bitrate|videoconfig)")))installCapture(method,config);
        }
    }
    private void installCapture(Executable executable,boolean config) {
        if(!hooked.add(executable))return;
        try { module.hook(executable).intercept(chain->{
            EncodingDiagnostics.Session session=log.active();
            if(session==null)return chain.proceed();
            Object owner=chain.getThisObject();
            // CaptureClient is a reusable factory; controllers/encoders belong to the session that created them.
            boolean factory=executable.getDeclaringClass().getName().equals("cn.ninebot.capture.CaptureClient")||config;
            try { if(owner!=null&&!factory) {
                synchronized(captureOwners) {
                    EncodingDiagnostics.Session prior=captureOwners.get(owner);
                    if(prior!=null&&prior!=session)session=null;
                    else if(prior==null&&captureOwners.size()<256)captureOwners.put(owner,session);
                }
            } } catch(Throwable ignored) { session=null; }
            Scope previous=context.get();
            if(previous!=null)session=previous.session();
            context.set(new Scope(session));
            String source=executable.getDeclaringClass().getSimpleName()+"."+(executable instanceof Constructor?"<init>":executable.getName())
                    +"("+String.join(",",Arrays.stream(executable.getParameterTypes()).map(Class::getSimpleName).toArray(String[]::new))+")";
            try {
                try {
                    List<?> args=chain.getArgs();
                    List<String> values=new ArrayList<>();
                    for(int i=0;i<args.size();i++) {
                        Object value=args.get(i);
                        String scalar=CaptureConfigReader.scalar(value,false);
                        if(scalar!=null)values.add("arg"+i+"="+scalar);
                        else if(value!=null&&CaptureConfigReader.videoConfig(value.getClass()))
                            values.add("arg"+i+" VideoConfig={"+CaptureConfigReader.fields(value,true)+"}");
                        else if(value instanceof MediaFormat)log.capture(session,source+".arg"+i,read((MediaFormat)value).describe());
                    }
                    if(!values.isEmpty())log.capture(session,source+".arguments",String.join(",",values));
                } catch(Throwable ignored) {}
                Object result=chain.proceed();
                try {
                    String fields=CaptureConfigReader.fields(chain.getThisObject(),config);
                    if(!fields.equals("未读取")||config)log.capture(session,source+".fields",fields);
                } catch(Throwable ignored) {}
                return result;
            } finally { if(previous==null)context.remove();else context.set(previous); }
        }); }
        catch(Throwable error) { hooked.remove(executable);frames.report("ENCODING capture hook unavailable "+executable.getDeclaringClass().getSimpleName()+" "+error.getClass().getSimpleName()); }
    }
    /** Rewrites the bitrate / frame-rate keys of a vehicle encoder's MediaFormat; returns whether anything changed. */
    private boolean override(MediaFormat format) {
        EncoderOverride override=frames.encoderOverride();
        if(format==null||!override.active()||log.active()==null)return false;
        try {
            if(override.overridesBitrate())format.setInteger(MediaFormat.KEY_BIT_RATE,override.bitrateBps());
            if(override.overridesFps())format.setInteger(MediaFormat.KEY_FRAME_RATE,override.fps());
            frames.report("OVERRIDE MediaCodec.configure "+override.describe());
            return true;
        } catch(RuntimeException e) { frames.report("OVERRIDE configure failed "+e.getClass().getSimpleName());return false; }
    }
    /** Live bitrate change for the encoders of the running vehicle session; the frame rate follows through the loop interval hook. */
    private void apply(EncoderOverride override) {
        if(log.active()==null)return;
        List<Object> codecs;synchronized(liveCodecs) { codecs=new ArrayList<>(liveCodecs.keySet()); }
        for(Object codec:codecs) {
            if(!(codec instanceof MediaCodec)||log.owner(codec)==null)continue;
            try {
                Bundle parameters=new Bundle();
                if(override.overridesBitrate())parameters.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,override.bitrateBps());
                if(parameters.isEmpty())continue;
                ((MediaCodec)codec).setParameters(parameters);
                frames.report("OVERRIDE live "+override.describe());
            } catch(Throwable e) { frames.report("OVERRIDE live failed "+e.getClass().getSimpleName()); }
        }
    }
    /**
     * VideoConfig getters feed every Ninebot encoder; the loop interval getter paces the bitmap encoder each iteration; the FFmpeg
     * recorder setters are the last word for the h264_mediacodec / mjpeg / mpeg2 path, including the rebuilds Ninebot performs when the
     * dashboard asks for another rate through RTCP.
     */
    private void installOverrides(Class<?> type) {
        String name=type.getName();
        boolean config=CaptureConfigReader.videoConfig(type),loop=name.equals("cn.ninebot.capture.encoder.LoopBitmapEncoder");
        boolean recorder=name.equals("cn.ninebot.capture.mpeg2.NbFFmpegFrameRecorder");
        if(!config&&!loop&&!recorder)return;
        // The recorder setters are declared by its javacv superclass; hooking them there covers every recorder instance.
        List<Method> methods=new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
        if(recorder)for(Class<?> parent=type.getSuperclass();parent!=null&&parent!=Object.class;parent=parent.getSuperclass())methods.addAll(Arrays.asList(parent.getDeclaredMethods()));
        for(Method method:methods) {
            if(Modifier.isStatic(method.getModifiers())||method.isSynthetic()||Modifier.isAbstract(method.getModifiers()))continue;
            String getter=method.getName();Class<?>[] types=method.getParameterTypes();
            int found=0;
            if(types.length==0)found=config&&getter.equals("getVideoBitrate")?1:config&&getter.equals("getFrameRate")?2:loop&&getter.equals("getMInterval")?3:0;
            else if(types.length==1&&recorder)found=getter.equals("setVideoBitrate")&&types[0]==int.class?4:getter.equals("setFrameRate")&&types[0]==double.class?5:0;
            final int kind=found;
            if(kind==0||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                if(kind>=4) {
                    try {
                        EncoderOverride override=frames.encoderOverride();
                        if(override.active()&&log.active()!=null) {
                            Object[] args=chain.getArgs().toArray();
                            if(kind==4&&override.overridesBitrate()&&!Integer.valueOf(override.bitrateBps()).equals(args[0])) { frames.report("OVERRIDE recorder bitrate "+args[0]+" -> "+override.bitrateBps());args[0]=Integer.valueOf(override.bitrateBps());return chain.proceed(args); }
                            if(kind==5&&override.overridesFps()&&!Double.valueOf(override.fps()).equals(args[0])) { frames.report("OVERRIDE recorder fps "+args[0]+" -> "+override.fps());args[0]=Double.valueOf(override.fps());return chain.proceed(args); }
                        }
                    } catch(RuntimeException ignored) {}
                    return chain.proceed();
                }
                Object result=chain.proceed();
                try {
                    EncoderOverride override=frames.encoderOverride();
                    if(!override.active()||log.active()==null)return result;
                    if(kind==1&&override.overridesBitrate())return Integer.valueOf(override.bitrateBps());
                    if(kind==2&&override.overridesFps())return Double.valueOf(override.fps());
                    if(kind==3&&override.overridesFps())return Integer.valueOf(override.intervalMs());
                } catch(RuntimeException ignored) {}
                return result;
            }); frames.report("OVERRIDE hook "+type.getSimpleName()+"."+getter); }
            catch(Throwable e) { hooked.remove(method);frames.report("OVERRIDE hook unavailable "+getter+" "+e.getClass().getSimpleName()); }
        }
    }
    private EncodingDiagnostics.Session captureSession() {
        Scope scoped=context.get();
        if(scoped!=null)return scoped.session();
        EncodingDiagnostics.Session current=log.active();if(current==null)return null;
        for(StackTraceElement frame:Thread.currentThread().getStackTrace())
            if(HookPolicy.captureClass(frame.getClassName()))return current;
        return null;
    }
    private void inspectCallback(Class<?> type) throws NoSuchMethodException {
        hookCallback(type.getMethod("onOutputFormatChanged",MediaCodec.class,MediaFormat.class),true);
        hookCallback(type.getMethod("onOutputBufferAvailable",MediaCodec.class,int.class,MediaCodec.BufferInfo.class),false);
    }
    private void hookCallback(Method method,boolean format) {
        if(Modifier.isAbstract(method.getModifiers())||!hooked.add(method))return;
        try { module.hook(method).intercept(chain->{
            try {
                Object codec=chain.getArg(0);EncodingDiagnostics.Session session=log.owner(codec);
                if(session!=null) {
                    if(format)log.format(session,codec,read((MediaFormat)chain.getArg(1)),true,"onOutputFormatChanged");
                    else buffer(session,codec,(MediaCodec.BufferInfo)chain.getArg(2));
                }
            } catch(Throwable ignored) {}
            return chain.proceed();
        }); } catch(Throwable error) { hooked.remove(method);frames.report("ENCODING callback hook unavailable "+method.getName()); }
    }
    private void buffer(EncodingDiagnostics.Session session,Object codec,MediaCodec.BufferInfo info) {
        if(info!=null)log.buffer(session,codec,info.presentationTimeUs,info.size,info.flags);
    }
    private static EncodingFormat read(MediaFormat format) {
        Map<String,Object> fields=new HashMap<>();
        if(format!=null) {
            try { fields.put("mime",format.getString("mime")); } catch(RuntimeException ignored) {}
            for(String key:new String[]{"width","height","frame-rate","bitrate","crop-left","crop-top","crop-right","crop-bottom"})
                try { if(format.containsKey(key))fields.put(key,format.getNumber(key)); } catch(RuntimeException ignored) {}
        }
        return EncodingFormat.read(fields);
    }
}
