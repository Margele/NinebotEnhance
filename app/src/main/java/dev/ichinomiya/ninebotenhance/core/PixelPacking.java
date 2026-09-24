package dev.ichinomiya.ninebotenhance.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class PixelPacking {
    /** Reads no last-row padding; some ImageReader buffers do not expose that padding. */
    public static void rgba(ByteBuffer input, int rowStride, int pixelStride, int width, int height, ByteBuffer output) {
        compose(input,rowStride,pixelStride,width,height,output,width,height,0xff000000);
    }
    /** Opaque top/right background, with every source pixel preserved at bottom-left. */
    public static void compose(ByteBuffer input,int rowStride,int pixelStride,int width,int height,ByteBuffer output,int frameWidth,int frameHeight,int color) {
        compose(input,rowStride,pixelStride,width,height,output,frameWidth,frameHeight,color,false);
    }
    /**
     * With {@code healEdges} the outermost pixel ring of the app content is replaced by its inner neighbours. A display whose
     * logical size is scaled into the buffer ("keep phone DPI") gets its last row and column only partly covered by the
     * scaled content, so SurfaceFlinger blends them with black: a dark hairline, visible against a light background.
     */
    public static void compose(ByteBuffer input,int rowStride,int pixelStride,int width,int height,ByteBuffer output,int frameWidth,int frameHeight,int color,boolean healEdges) {
        compose(input,rowStride,pixelStride,width,height,output,frameWidth,frameHeight,frameHeight-height,color,healEdges);
    }
    /** The app's first row is {@code top}; the rows above and below it and the columns to its right take the background colour. */
    public static void compose(ByteBuffer input,int rowStride,int pixelStride,int width,int height,ByteBuffer output,int frameWidth,int frameHeight,int top,int color,boolean healEdges) {
        if(width<1||height<1||frameWidth<width||frameHeight<height||top<0||(long)top+height>frameHeight||pixelStride<4||rowStride<(long)width*pixelStride
                ||(long)frameWidth*frameHeight*4>output.capacity())throw new IllegalArgumentException("Invalid canvas layout");
        BandColor.requireOpaque(color);
        int base=input.position();long end=(long)base+(long)(height-1)*rowStride+(long)(width-1)*pixelStride+4;
        if(end>input.limit())throw new IllegalArgumentException("Incomplete RGBA buffer");
        output.clear();int rgba=((color&0xffffff)<<8)|255,packed=output.order()==ByteOrder.BIG_ENDIAN?rgba:Integer.reverseBytes(rgba);
        for(int i=0;i<frameWidth*top;i++)output.putInt(packed);
        for(int y=0;y<height;y++){
            int start=base+y*rowStride;
            if(pixelStride==4){ByteBuffer row=input.duplicate();row.position(start);row.limit(start+width*4);output.put(row);}
            else for(int x=0;x<width;x++)for(int c=0;c<4;c++)output.put(input.get(start+x*pixelStride+c));
            for(int x=width;x<frameWidth;x++)output.putInt(packed);
        }
        for(int i=0;i<frameWidth*(frameHeight-top-height);i++)output.putInt(packed);
        output.flip();
        if(healEdges&&width>2&&height>2)healEdges(output,frameWidth,top,width,height);
    }
    static void healEdges(ByteBuffer output,int frameWidth,int top,int width,int height){
        int stride=frameWidth*4;
        for(int y=0;y<height;y++){int row=(top+y)*stride;output.putInt(row,output.getInt(row+4));output.putInt(row+(width-1)*4,output.getInt(row+(width-2)*4));}
        int first=top*stride,second=(top+1)*stride,last=(top+height-1)*stride,before=(top+height-2)*stride;
        for(int x=0;x<width*4;x+=4){output.putInt(first+x,output.getInt(second+x));output.putInt(last+x,output.getInt(before+x));}
    }
    private PixelPacking() {}
}
