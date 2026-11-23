package com.hipster.tenoridroid;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

public class NativeAudio {
    public static boolean lib;
    static { try { System.loadLibrary("tenori"); lib=true; } catch (Throwable t) { lib=false; } }
    private int sr=44100, block=512, nbuf=3;
    private boolean ok;
    public static NativeAudio create(Context c,int bpm,boolean fast){ return new NativeAudio(c,bpm,fast); }
    private NativeAudio(Context c,int bpm,boolean fast){
        try { int n=AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC); if(n>=8000&&n<=48000) sr=n; } catch(Throwable ignored){}
        block=fast?256:512; nbuf=fast?2:3;
        if(Build.VERSION.SDK_INT>=17&&fast){
            try { AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
                String fp=am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                if(fp!=null){ int v=Integer.parseInt(fp); if(v>=64&&v<=512) block=v; } } catch(Throwable ignored){}
        }
        ok = lib && nInit(sr,block,nbuf)==0;
        if(ok){ setBpm(bpm); setMaster(85); }
    }
    public boolean isOk(){ return ok; }
    public int sr(){ return sr; }
    public int latencyMs(){ return ok? block*nbuf*1000/sr+25 : 0; }
    public long lookaheadNs(){ return (long)block*2000000000L/sr; }
    public int delayFrames(long t){ return ok? nDelay(t):0; }
    public void noteOn(int note,int vel,Layer l,int snd,int delay,int gate){
        if(ok) nNoteOn(note,vel,snd,delay,gate,l.cutoff,l.attack,l.decay,l.sustain,l.release);
    }
    public void panic(){ if(ok) nPanic(); }
    public void setBpm(int bpm){ if(ok) nDelayCfg(0.16f,0.32f,(int)(sr*60f/Math.max(20,bpm)*0.375f)); }
    public void setMaster(int p){ if(ok) nMaster(p/100f); }
    public void release(){ if(ok){ nRelease(); ok=false; } }
    public int xruns(){ return ok? nStats():0; }
    public String name(){ return ok? ("OpenSL "+sr+"/"+block+"x"+nbuf) : "NO AUDIO (MIDI only)"; }
    private static native int nInit(int sr,int block,int nbuf);
    private static native void nNoteOn(int note,int vel,int snd,int delay,int gate,float cut,float a,float d,float s,float r);
    private static native int nDelay(long when);
    private static native void nPanic();
    private static native void nMaster(float v);
    private static native void nDelayCfg(float wet,float fb,int len);
    private static native int nStats();
    private static native void nRelease();
}
