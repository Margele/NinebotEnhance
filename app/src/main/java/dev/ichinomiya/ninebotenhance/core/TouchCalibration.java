package dev.ichinomiya.ninebotenhance.core;

/**
 * Affine map from a touch panel's normalized raw axes (0..1) to normalized frame coordinates (0..1), solved by least squares from
 * taps on known targets. It absorbs the panel's offset, scale, rotation and mirroring in one step, so a calibrated panel ignores
 * the mounting rotation setting. Stored as six comma separated numbers.
 */
public record TouchCalibration(float a,float b,float c,float d,float e,float f){
    /** Target positions in normalized frame coordinates, tapped in this order. */
    public static final float[][] TARGETS={{0.1f,0.1f},{0.9f,0.1f},{0.9f,0.9f},{0.1f,0.9f},{0.5f,0.5f}};
    /** Largest tolerated distance between a tap and its target after the fit, as a fraction of the frame. */
    public static final float MAX_RESIDUAL=0.08f;
    public TouchCalibration{
        for(float v:new float[]{a,b,c,d,e,f})if(Float.isNaN(v)||Float.isInfinite(v))throw new IllegalArgumentException("calibration");
        float det=a*e-b*d;
        if(Math.abs(det)<0.05f||Math.abs(det)>20f)throw new IllegalArgumentException("calibration degenerate");
    }
    public float[] apply(float nx,float ny){return new float[]{a*nx+b*ny+c,d*nx+e*ny+f};}
    /** Fit to the samples {@code raw[i]={nx,ny}} tapped on {@code target[i]={u,v}}; at least three, not collinear, all within tolerance. */
    public static TouchCalibration solve(float[][] raw,float[][] target){
        if(raw==null||target==null||raw.length<3||raw.length!=target.length)throw new IllegalArgumentException("samples");
        double[][] m=new double[3][3];double[] ru=new double[3],rv=new double[3];
        for(int i=0;i<raw.length;i++){
            double[] row={raw[i][0],raw[i][1],1};
            for(int r=0;r<3;r++){for(int c=0;c<3;c++)m[r][c]+=row[r]*row[c];ru[r]+=row[r]*target[i][0];rv[r]+=row[r]*target[i][1];}
        }
        double[] pu=solve3(m,ru),pv=solve3(m,rv);
        TouchCalibration fit=new TouchCalibration((float)pu[0],(float)pu[1],(float)pu[2],(float)pv[0],(float)pv[1],(float)pv[2]);
        for(int i=0;i<raw.length;i++){
            float[] p=fit.apply(raw[i][0],raw[i][1]);
            if(Math.hypot(p[0]-target[i][0],p[1]-target[i][1])>MAX_RESIDUAL)throw new IllegalArgumentException("samples inconsistent");
        }
        return fit;
    }
    public String encode(){return a+","+b+","+c+","+d+","+e+","+f;}
    /** Null for an empty or unreadable text. */
    public static TouchCalibration decode(String text){
        if(text==null||text.trim().isEmpty())return null;
        String[] parts=text.trim().split(",");
        if(parts.length!=6)return null;
        try{
            float[] v=new float[6];for(int i=0;i<6;i++)v[i]=Float.parseFloat(parts[i].trim());
            return new TouchCalibration(v[0],v[1],v[2],v[3],v[4],v[5]);
        }catch(IllegalArgumentException e){return null;}
    }
    private static double[] solve3(double[][] m,double[] r){
        double det=m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1])-m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0])+m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0]);
        if(Math.abs(det)<1e-9)throw new IllegalArgumentException("samples collinear");
        double[] out=new double[3];
        for(int k=0;k<3;k++){
            double[][] t=new double[3][3];
            for(int i=0;i<3;i++)for(int j=0;j<3;j++)t[i][j]=j==k?r[i]:m[i][j];
            out[k]=(t[0][0]*(t[1][1]*t[2][2]-t[1][2]*t[2][1])-t[0][1]*(t[1][0]*t[2][2]-t[1][2]*t[2][0])+t[0][2]*(t[1][0]*t[2][1]-t[1][1]*t[2][0]))/det;
        }
        return out;
    }
}
