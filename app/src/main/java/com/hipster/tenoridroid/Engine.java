package com.hipster.tenoridroid;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.Random;

class Scale {
    static final String[] NAMES={"Chromatic","Major","Minor","Dorian","Phrygian","Lydian","Mixolydian","Locrian","Harm.Min","Penta Maj","Penta Min","Blues","WholeTone","In (JP)","Hijaz","Octaves"};
    static final String[] ROOTS={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
    private static final int[][] S={{0,1,2,3,4,5,6,7,8,9,10,11},{0,2,4,5,7,9,11},{0,2,3,5,7,8,10},{0,2,3,5,7,9,10},{0,1,3,5,7,8,10},{0,2,4,6,7,9,11},{0,2,4,5,7,9,10},{0,1,3,5,6,8,10},{0,2,3,5,7,8,11},{0,2,4,7,9},{0,3,5,7,10},{0,3,5,6,7,10},{0,2,4,6,8,10},{0,1,5,7,8},{0,1,4,5,7,8,11},{0,12}};
    static int note(int sc,int root,int oct,int row){ int[] s=S[sc%S.length]; int n=s.length,d=row%n,o=row/n; int v=12*(oct+1+o)+root+s[d]; return v<0?0:(v>127?127:v); }
    static boolean isRoot(int sc,int row){ return row%S[sc%S.length].length==0; }
}

class Layer {
    final byte[] grid=new byte[256];
    int sound=0,channel=0,program=-1,octave=3,velocity=100,gate=60,mode=0,len=16,div=1;
    boolean kit=false,mute=false,solo=false;
    float cutoff=0.62f,attack=0.004f,decay=0.25f,sustain=0.35f,release=0.12f;
    int pos=-1,dir=1; long trigAt=0;
    static final String[] MODES={"Score","Bounce","Random","Drunk","Push"};
    static final int[] DIVS={1,2,3,4,6,8};
    static final int[] DRUM={36,38,42,46,39,37,45,47,50,51,49,56,70,75,60,64};
    static final int[] KITSND={6,7,8,9,10,12,11,11,11,8,9,13,5,12,11,11};
    void clear(){ java.util.Arrays.fill(grid,(byte)0); }
}

public class Engine {
    public static final int NL=16;
    public static final String[] SOUNDS={"Sine","Triangle","Saw","Square","FM Bell","Noise","Kick","Snare","Hat","OpenHat","Clap","Tom","Rim","Cowbell","Sub","Pluck"};
    public final Layer[] layers=new Layer[NL];
    public final MidiHub midi=new MidiHub();
    public volatile NativeAudio audio;
    public volatile boolean playing,audioOn=true,midiOn=true,clockOut=false,extSync=false,thru=false,record=false;
    public volatile int bpm=120,swing=0,scale=1,root=0,sel=0,midiOffsetMs=70,masterVol=85;
    public final byte[][] scenes=new byte[8][NL*256];
    public volatile int scene=0,songPos=0,songLen=4,barsPerScene=1;
    public volatile boolean songOn=false;
    private Thread th; private volatile boolean alive;
    private long nextStep; private int step,extClocks;
    private final Random rnd=new Random();
    private final Object ql=new Object();
    private final PriorityQueue<Ev> q=new PriorityQueue<Ev>();
    private final ArrayDeque<Ev> pool=new ArrayDeque<Ev>();
    private static final class Ev implements Comparable<Ev>{ long t; int st,d1,d2; public int compareTo(Ev o){return t<o.t?-1:(t>o.t?1:0);} }

    public Engine(){
        for(int i=0;i<NL;i++){ Layer l=new Layer(); layers[i]=l; l.channel=i; l.sound=i%6; l.octave=3+(i%3); }
        layers[0].kit=true; layers[0].channel=9; layers[0].gate=30;
        Layer d=layers[0];
        for(int x=0;x<16;x+=4) d.grid[x]=2;
        d.grid[16+4]=1; d.grid[16+12]=1;
        for(int x=2;x<16;x+=4) d.grid[32+x]=1;
        Layer b=layers[1]; b.sound=14; b.octave=2; b.gate=45; b.velocity=96;
        int[] st={0,3,6,8,11,14}, rw={0,0,7,0,3,5};
        for(int k=0;k<st.length;k++) b.grid[rw[k]*16+st[k]]=1;
        saveScene(0);
    }
    public void start(){ if(th!=null)return; alive=true; th=new Thread(loop,"tenori-seq"); th.start(); }
    public void shutdown(){ alive=false; }
    public void play(){ clearQueue(); for(Layer l:layers){l.pos=-1;l.dir=1;} step=0; extClocks=0; nextStep=System.nanoTime()+30000000L; playing=true; midi.raw(0xFA); }
    public void stopPlay(){ playing=false; panic(); midi.raw(0xFC); }
    public void panic(){ clearQueue(); for(int c=0;c<16;c++){midi.send(0xB0|c,123,0);midi.send(0xB0|c,120,0);} NativeAudio a=audio; if(a!=null)a.panic(); }
    public long stepNanos(){ return 60000000000L/Math.max(20,bpm)/4; }
    private long off(){ return midiOffsetMs*1000000L; }

    private final Runnable loop=new Runnable(){ public void run(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        nextStep=System.nanoTime()+50000000L;
        while(alive){
            long now=System.nanoTime(),sn=stepNanos();
            if(playing&&!extSync){
                if(nextStep<now-200000000L) nextStep=now;
                NativeAudio a=audio; long look=a!=null?a.lookaheadNs():20000000L; int guard=0;
                while(nextStep-look<=now&&guard++<4){
                    long t=nextStep+(((step&1)==1)?(long)(sn*swing/200f):0);
                    fire(step,t,sn); step++; nextStep+=sn;
                }
            }
            drain(now);
            try{ Thread.sleep(1); }catch(InterruptedException ignored){}
        }
    }};

    private void fire(int s,long t,long sn){
        if(songOn&&s>0&&s%(16*barsPerScene)==0){ songPos=(songPos+1)%Math.max(1,songLen); loadScene(songPos); }
        boolean anySolo=false; for(Layer l:layers) if(l.solo) anySolo=true;
        for(int i=0;i<NL;i++){
            Layer l=layers[i];
            if(l.mute||(anySolo&&!l.solo)) continue;
            if(s%l.div!=0) continue;
            advance(l); int x=l.pos,hit=0;
            for(int y=0;y<16;y++){
                int g=l.grid[y*16+x]; if(g==0) continue;
                int note=l.kit?Layer.DRUM[y]:Scale.note(scale,root,l.octave,y);
                int vel=Math.min(127,g==2?(int)(l.velocity*1.25f):l.velocity);
                trigger(l,y,note,vel,t,(long)(sn*l.div*l.gate/100f)); hit++;
            }
            if(hit>0) l.trigAt=SystemClock.uptimeMillis();
        }
        if(clockOut) for(int k=0;k<6;k++) push(t+off()+k*sn/6,0xF8,0,0);
    }
    private void advance(Layer l){
        int n=Math.max(1,Math.min(16,l.len));
        switch(l.mode){
            case 1:{ int p=l.pos+l.dir; if(p>=n){l.dir=-1;p=n>1?n-2:0;} else if(p<0){l.dir=1;p=n>1?1:0;} l.pos=p; break; }
            case 2: l.pos=rnd.nextInt(n); break;
            case 3: l.pos=(l.pos+rnd.nextInt(3)-1+n)%n; break;
            case 4: if(l.pos<0)l.pos=0; break;
            default: l.pos=(l.pos+1)%n;
        }
    }
    public void pushLayer(int i){
        Layer l=layers[i]; l.pos=(l.pos+1)%Math.max(1,l.len);
        long now=System.nanoTime(),sn=stepNanos();
        for(int y=0;y<16;y++){ int g=l.grid[y*16+l.pos]; if(g==0)continue;
            int note=l.kit?Layer.DRUM[y]:Scale.note(scale,root,l.octave,y);
            trigger(l,y,note,l.velocity,now,sn*l.gate/100); }
        l.trigAt=SystemClock.uptimeMillis();
    }
    private void trigger(Layer l,int row,int note,int vel,long t,long gate){
        NativeAudio a=audio;
        if(audioOn&&a!=null){ int snd=l.kit?Layer.KITSND[row]:l.sound; a.noteOn(note,vel,l,snd,a.delayFrames(t),(int)(gate*a.sr()/1000000000L)); }
        if(midiOn){ long o=off(); push(t+o,0x90|l.channel,note,vel); push(t+gate+o,0x80|l.channel,note,0); }
    }
    public void audition(int row){
        Layer l=layers[sel];
        int note=l.kit?Layer.DRUM[row]:Scale.note(scale,root,l.octave,row);
        trigger(l,row,note,l.velocity,System.nanoTime(),140000000L);
        l.trigAt=SystemClock.uptimeMillis();
    }
    private void push(long t,int st,int d1,int d2){
        synchronized(ql){ Ev e=pool.poll(); if(e==null)e=new Ev(); e.t=t;e.st=st;e.d1=d1;e.d2=d2; q.add(e); }
    }
    private void clearQueue(){ synchronized(ql){ Ev e; while((e=q.poll())!=null) if(pool.size()<256)pool.addLast(e); } }
    private void drain(long now){
        for(;;){ Ev e;
            synchronized(ql){ e=q.peek(); if(e==null||e.t>now)return; q.poll(); }
            if(e.st>=0xF8) midi.raw(e.st); else midi.send(e.st,e.d1,e.d2);
            synchronized(ql){ if(pool.size()<256)pool.addLast(e); }
        }
    }
    public void midiIn(int st,int d1,int d2){
        if(st==0xF8){ if(extSync&&playing&&++extClocks>=6){ extClocks=0; fire(step,System.nanoTime(),stepNanos()); step++; } return; }
        if(st==0xFA){ extClocks=0; step=0; if(extSync)playing=true; return; }
        if(st==0xFC){ if(extSync)playing=false; return; }
        if(thru) midi.send(st,d1,d2);
        if((st&0xF0)==0x90&&d2>0){
            Layer l=layers[sel];
            for(int y=0;y<16;y++){ int n=l.kit?Layer.DRUM[y]:Scale.note(scale,root,l.octave,y);
                if(n==d1){ if(record&&playing&&l.pos>=0) l.grid[y*16+l.pos]=1; audition(y); break; } }
        }
    }
    public void saveScene(int i){ for(int k=0;k<NL;k++) System.arraycopy(layers[k].grid,0,scenes[i],k*256,256); scene=i; }
    public void loadScene(int i){ for(int k=0;k<NL;k++) System.arraycopy(scenes[i],k*256,layers[k].grid,0,256); scene=i; }
    public void sendPC(int i){ Layer l=layers[i]; if(l.program>=0) midi.send(0xC0|l.channel,l.program&127,0); }
    public void randomize(int i,int dens){ Layer l=layers[i]; for(int k=0;k<256;k++) l.grid[k]=(byte)(rnd.nextInt(100)<dens?(rnd.nextInt(8)==0?2:1):0); }
}
