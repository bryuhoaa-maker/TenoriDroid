package com.hipster.tenoridroid;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.Arrays;

public class GridView extends View {
    private final Engine e; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); private final RectF r=new RectF();
    private float cs=20f; private final int[] last=new int[10],pv=new int[10],colors=new int[16];
    public boolean accent=false,ghost=true,audition=true;
    public GridView(Context c,Engine e){
        super(c); this.e=e; Arrays.fill(last,-1);
        for(int i=0;i<16;i++) colors[i]=Color.HSVToColor(new float[]{(i*24f)%360f,0.65f,1f});
        setKeepScreenOn(true);
    }
    protected void onMeasure(int w,int h){ int s=Math.min(MeasureSpec.getSize(w),MeasureSpec.getSize(h)); setMeasuredDimension(s,s); }
    protected void onDraw(Canvas c){
        int W=getWidth(); if(W<=0)return; cs=W/16f;
        Layer L=e.layers[e.sel]; long now=SystemClock.uptimeMillis();
        c.drawColor(0xFF0A0C10);
        for(int y=0;y<16;y++) for(int x=0;x<16;x++){
            float sx=x*cs,sy=(15-y)*cs;
            r.set(sx+1.5f,sy+1.5f,sx+cs-1.5f,sy+cs-1.5f);
            int g=L.grid[y*16+x],col;
            if(g!=0) col=g==2?0xFFFFD24A:colors[e.sel];
            else{ boolean rr=!L.kit&&Scale.isRoot(e.scale,y);
                col=((x>>2)&1)==0?(rr?0xFF1C2430:0xFF151A22):(rr?0xFF18202B:0xFF111620); }
            p.setColor(col); c.drawRect(r,p);
            if(g==0&&ghost) for(int i=0;i<16;i++){
                if(i==e.sel)continue; Layer o=e.layers[i];
                if(o.mute||o.grid[y*16+x]==0)continue;
                p.setColor(colors[i]&0x22FFFFFF); c.drawRect(r,p); break;
            }
            if(e.playing&&x==L.pos){ long age=now-L.trigAt; int a=g!=0?(age<110?0xB0:0x50):0x28;
                p.setColor((a<<24)|0xFFFFFF); c.drawRect(r,p); }
        }
        if(e.playing&&L.pos>=0){
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(0xFF39D0FF);
            c.drawRect(L.pos*cs+1,1,L.pos*cs+cs-1,W-1,p); p.setStyle(Paint.Style.FILL);
        }
        postInvalidateDelayed(Build.VERSION.SDK_INT<18?40:33);
    }
    private int cell(float px,float py){ int x=(int)(px/cs),ry=(int)(py/cs);
        if(x<0||x>15||ry<0||ry>15)return -1; return (15-ry)*16+x; }
    public boolean onTouchEvent(MotionEvent ev){
        int a=ev.getActionMasked(),idx=ev.getActionIndex(); Layer L=e.layers[e.sel];
        switch(a){
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN:{
                int pid=ev.getPointerId(idx); if(pid>9)return true;
                int cl=cell(ev.getX(idx),ev.getY(idx)); if(cl<0)return true;
                int cur=L.grid[cl],v=cur==0?(accent?2:1):(accent&&cur==1?2:0);
                L.grid[cl]=(byte)v; last[pid]=cl; pv[pid]=v;
                if(v!=0&&audition) e.audition(cl/16);
                if(L.mode==4) e.pushLayer(e.sel);
                break; }
            case MotionEvent.ACTION_MOVE:{
                for(int i=0;i<ev.getPointerCount();i++){
                    int pid=ev.getPointerId(i); if(pid>9)continue;
                    int cl=cell(ev.getX(i),ev.getY(i));
                    if(cl>=0&&cl!=last[pid]){ last[pid]=cl; L.grid[cl]=(byte)pv[pid];
                        if(pv[pid]!=0&&audition) e.audition(cl/16); }
                }
                break; }
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP:{
                int pid=ev.getPointerId(idx); if(pid<=9)last[pid]=-1; break; }
            case MotionEvent.ACTION_CANCEL: Arrays.fill(last,-1); break;
        }
        invalidate(); return true;
    }
}
