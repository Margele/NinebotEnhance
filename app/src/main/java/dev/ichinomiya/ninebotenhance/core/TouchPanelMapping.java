package dev.ichinomiya.ninebotenhance.core;

/** Panel coordinates to picture pixels. The rotation is the panel's mounting in quarter turns clockwise relative to the picture. */
public final class TouchPanelMapping {
    public static float[] map(int rawX,int rawY,TouchPanelProbe.Axis x,TouchPanelProbe.Axis y,int rotation,int width,int height){
        if(width<=0||height<=0)throw new IllegalArgumentException("picture "+width+"x"+height);
        if(rotation<0||rotation>=TouchPanel.ROTATIONS)throw new IllegalArgumentException("rotation "+rotation);
        float nx=x.normalize(rawX),ny=y.normalize(rawY);
        switch(rotation){
            case 1:return new float[]{(1-ny)*width,nx*height};
            case 2:return new float[]{(1-nx)*width,(1-ny)*height};
            case 3:return new float[]{ny*width,(1-nx)*height};
            default:return new float[]{nx*width,ny*height};
        }
    }
    private TouchPanelMapping(){}
}
