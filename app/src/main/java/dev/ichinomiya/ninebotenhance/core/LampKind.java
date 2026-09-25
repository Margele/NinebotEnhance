package dev.ichinomiya.ninebotenhance.core;

/**
 * The lamp controllers the module can drive, each with its own GATT layout and command set. A positional kind takes an absolute
 * height and the module steps it by a percentage of its travel; a timed kind only knows "run up", "run down" and "stop", so one
 * press runs its motor for the time set in the lamp screen and then stops it.
 */
public enum LampKind {
    /** TX hoist (MOTORE): FFE0, write FFE3, notify FFE4; six digit password; absolute height inside the travel limits it reports. */
    TX(0,"TX 灯控",TxLampProtocol.SERVICE,TxLampProtocol.CHAR_WRITE,TxLampProtocol.CHAR_NOTIFY,true,TxLampProtocol.PASSWORD_LENGTH,LampSettings.TOP_MARGIN,TxLampProtocol.MAX_POSITION),
    /** 摩灯客 headlight ESC (“蓝牙电调控制”): FFF0, write FFF2, notify FFF1; six character password; absolute 0–100 with no readback. */
    ESC(1,"蓝牙电调",EscLampProtocol.SERVICE,EscLampProtocol.CHAR_WRITE,EscLampProtocol.CHAR_NOTIFY,true,EscLampProtocol.PASSWORD_LENGTH,0,EscLampProtocol.MAX_POSITION),
    /** 摩灯客 canopy / lift controller: FFF0, single FFF2; no password; thirty absolute positions the device reports back. */
    CANOPY(2,"摩灯客",CanopyLampProtocol.SERVICE,CanopyLampProtocol.CHAR,CanopyLampProtocol.CHAR,true,0,0,CanopyLampProtocol.MAX_POSITION),
    /** SG (JUXUN) electric lift: FFE0, single FFE1; no password; jog only, no position. */
    SG(3,"SG 灯控",SgLampProtocol.SERVICE,SgLampProtocol.CHAR,SgLampProtocol.CHAR,false,0,0,0);
    public final int id;public final String label,service,write,notify;
    /** Whether the device goes to an absolute height; otherwise it is jogged for a time. */
    public final boolean positional;
    /** Password length the device expects, 0 when it has none the module needs. */
    public final int passwordLength;
    /** Raw units under the reported upper limit that the device never reaches itself. */
    public final int topMargin;
    public final int maxPosition;
    LampKind(int id,String label,String service,String write,String notify,boolean positional,int passwordLength,int topMargin,int maxPosition){
        this.id=id;this.label=label;this.service=service;this.write=write;this.notify=notify;this.positional=positional;
        this.passwordLength=passwordLength;this.topMargin=topMargin;this.maxPosition=maxPosition;
    }
    public static LampKind of(int id){for(LampKind kind:values())if(kind.id==id)return kind;return TX;}
    public boolean needsPassword(){return passwordLength>0;}
    /** Whether the device shares one characteristic for writes and notifications. */
    public boolean singleCharacteristic(){return write.equals(notify);}
    public boolean validPassword(String password){
        return switch(this){
            case TX->TxLampProtocol.validPassword(password);
            case ESC->EscLampProtocol.validPassword(password);
            default->true;
        };
    }
    /**
     * Whether a scan result looks like this kind of device: the TX and SG firmwares advertise a telling name, the two 摩灯客
     * controllers only their FFF0 service (the ESC usually names itself {@code product@id}).
     */
    public boolean matches(String name,boolean advertisesService){
        return switch(this){
            case TX->TxLampProtocol.lampName(name);
            case SG->SgLampProtocol.lampName(name);
            case ESC->advertisesService||(name!=null&&name.indexOf('@')>=0);
            case CANOPY->advertisesService;
        };
    }
}
