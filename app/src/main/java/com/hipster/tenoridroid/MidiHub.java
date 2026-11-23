package com.hipster.tenoridroid;
import android.content.Context;
import android.hardware.usb.*;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class MidiHub {
    public interface Port { String name(); boolean ready(); void send(byte[] b,int n); void close(); }
    public interface In { void midiIn(int st,int d1,int d2); }
    public final CopyOnWriteArrayList<Port> ports=new CopyOnWriteArrayList<Port>();
    public volatile In in;
    private final ThreadLocal<byte[]> buf=new ThreadLocal<byte[]>(){ protected byte[] initialValue(){ return new byte[3]; } };
    public static int len(int st){
        if(st>=0xF0){ if(st==0xF2)return 3; if(st==0xF1||st==0xF3)return 2; return 1; }
        int c=st&0xF0; return (c==0xC0||c==0xD0)?2:3;
    }
    public void send(int st,int d1,int d2){
        if(ports.isEmpty())return; byte[] b=buf.get(); int n=len(st);
        b[0]=(byte)st; b[1]=(byte)d1; b[2]=(byte)d2;
        for(Port p:ports) if(p.ready()) p.send(b,n);
    }
    public void raw(int v){ if(ports.isEmpty())return; byte[] m=buf.get(); m[0]=(byte)v; for(Port p:ports) if(p.ready()) p.send(m,1); }
    public void closeAll(){ for(Port p:ports) p.close(); ports.clear(); }
    public String status(){
        if(ports.isEmpty()) return "MIDI: нет портов";
        StringBuilder s=new StringBuilder("MIDI:");
        for(Port p:ports) s.append(' ').append(p.name()).append(p.ready()?"OK":"..");
        return s.toString();
    }
}

class UsbMidi implements MidiHub.Port {
    private UsbDeviceConnection conn; private UsbEndpoint epIn,epOut; private UsbInterface iface;
    private Thread reader; private volatile boolean run,ok;
    private final MidiHub hub; private final String label; private final Object wl=new Object(); private final byte[] pkt=new byte[4];
    UsbMidi(MidiHub hub,String label){ this.hub=hub; this.label=label; }
    static List<UsbDevice> find(UsbManager um){
        List<UsbDevice> out=new ArrayList<UsbDevice>();
        for(UsbDevice d:um.getDeviceList().values()) if(midiIf(d)!=null) out.add(d);
        return out;
    }
    private static UsbInterface midiIf(UsbDevice d){
        UsbInterface best=null;
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface f=d.getInterface(i);
            if(f.getInterfaceClass()==UsbConstants.USB_CLASS_AUDIO&&f.getInterfaceSubclass()==3){
                if(f.getEndpointCount()>0) return f; best=f;
            }
        }
        return best;
    }
    boolean open(UsbManager um,UsbDevice dev){
        iface=midiIf(dev); if(iface==null) return false;
        for(int i=0;i<iface.getEndpointCount();i++){
            UsbEndpoint e=iface.getEndpoint(i);
            if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){ if(e.getDirection()==UsbConstants.USB_DIR_IN) epIn=e; else epOut=e; }
        }
        if(epOut==null) return false;
        conn=um.openDevice(dev); if(conn==null) return false;
        if(!conn.claimInterface(iface,true)){ conn.close(); conn=null; return false; }
        ok=true; run=true;
        if(epIn!=null){ reader=new Thread(rd,"usb-midi-in"); reader.start(); }
        return true;
    }
    public String name(){ return "USB:"+label; }
    public boolean ready(){ return ok; }
    public void send(byte[] m,int len){
        if(!ok||conn==null) return;
        int st=m[0]&0xFF,cin;
        if(st>=0xF8) cin=0xF; else if(st==0xF2) cin=0x3; else if(st==0xF1||st==0xF3) cin=0x2; else cin=(st>>4)&0x0F;
        synchronized(wl){ pkt[0]=(byte)cin; pkt[1]=m[0]; pkt[2]=len>1?m[1]:0; pkt[3]=len>2?m[2]:0; conn.bulkTransfer(epOut,pkt,4,30); }
    }
    private final Runnable rd=new Runnable(){ public void run(){
        byte[] b=new byte[64];
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        while(run){
            int n=conn.bulkTransfer(epIn,b,b.length,60); if(n<=0) continue;
            for(int i=0;i+3<n;i+=4){
                int cin=b[i]&0x0F,s=b[i+1]&0xFF; if(s==0) continue;
                MidiHub.In cb=hub.in; if(cb==null) continue;
                switch(cin){
                    case 0x8: case 0x9: case 0xB: case 0xE: cb.midiIn(s,b[i+2]&0x7F,b[i+3]&0x7F); break;
                    case 0xC: case 0xD: cb.midiIn(s,b[i+2]&0x7F,0); break;
                    case 0x5: case 0xF: cb.midiIn(s,0,0); break;
                    default: break;
                }
            }
        }
    }};
    public void close(){
        ok=false; run=false;
        try{ if(reader!=null) reader.join(150); }catch(InterruptedException ignored){}
        if(conn!=null){ if(iface!=null) conn.releaseInterface(iface); conn.close(); conn=null; }
    }
}

class SppMidi implements MidiHub.Port {
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothSocket sock; private OutputStream os; private InputStream is;
    private volatile boolean ok,run; private final MidiHub hub; private final String label; private Thread rd;
    SppMidi(MidiHub hub,String label){ this.hub=hub; this.label=label; }
    public String name(){ return "BT:"+label; }
    public boolean ready(){ return ok; }
    boolean connect(final BluetoothDevice d){
        try{
            sock=d.createRfcommSocketToServiceRecord(SPP); sock.connect();
            os=sock.getOutputStream(); is=sock.getInputStream(); ok=true; run=true;
            rd=new Thread(new Runnable(){ public void run(){
                byte[] b=new byte[128]; int st=0;
                while(run){
                    try{
                        int n=is.read(b); if(n<=0) continue;
                        MidiHub.In in=hub.in; if(in==null) continue;
                        for(int i=0;i<n;i++){
                            int v=b[i]&0xFF;
                            if((v&0x80)!=0){ st=v; if(st>=0xF8){ in.midiIn(st,0,0); st=0; } }
                            else if(st!=0){ int l=MidiHub.len(st);
                                if(l==3&&i+1<n){ in.midiIn(st,v,b[++i]&0x7F); st=0; } else { in.midiIn(st,v,0); st=0; } }
                        }
                    }catch(Exception e){ ok=false; return; }
                }
            }},"spp-in"); rd.start();
            return true;
        }catch(Exception e){ close(); return false; }
    }
    public void send(byte[] m,int len){ if(!ok)return; try{ synchronized(this){ os.write(m,0,len); os.flush(); } }catch(Exception e){ ok=false; } }
    public void close(){ ok=false; run=false; try{ if(sock!=null)sock.close(); }catch(Exception ignored){} sock=null; }
}

class RtpMidi implements MidiHub.Port {
    private DatagramSocket ctl,dat; private InetAddress peer; private int peerCtl=5004,peerDat=5005;
    private final MidiHub hub; private final String label;
    private volatile boolean ok,run=true; private int ssrc,token,seq;
    private final long t0=System.nanoTime(); private long offset=0,lastSync,lastFb;
    private WifiManager.WifiLock wl; private final byte[] tx=new byte[64];
    RtpMidi(MidiHub hub,String label){ this.hub=hub; this.label=label; }
    public String name(){ return "NET:"+label; }
    public boolean ready(){ return ok; }
    private static long t10k(long t0){ return (System.nanoTime()-t0)/100000L; }
    private static void p32(byte[] b,int i,int v){ b[i]=(byte)(v>>>24); b[i+1]=(byte)(v>>>16); b[i+2]=(byte)(v>>>8); b[i+3]=(byte)v; }
    private static int g32(byte[] b,int i){ return ((b[i]&0xFF)<<24)|((b[i+1]&0xFF)<<16)|((b[i+2]&0xFF)<<8)|(b[i+3]&0xFF); }
    private static long g64(byte[] b,int i){ return ((long)g32(b,i)<<32)|(g32(b,i+4)&0xFFFFFFFFL); }
    private static void p64(byte[] b,int i,long v){ p32(b,i,(int)(v>>>32)); p32(b,i+4,(int)v); }
    boolean start(Context c,String host,boolean listener){
        ssrc=(int)(System.nanoTime()&0x7FFFFFFF); token=ssrc;
        try{
            if(listener){ ctl=new DatagramSocket(5004); dat=new DatagramSocket(5005); }
            else{
                boolean bound=false; int[] bases={5004,5006,5008,5010};
                for(int b:bases){ try{ ctl=new DatagramSocket(b); dat=new DatagramSocket(b+1); bound=true; break; }
                    catch(Exception e){ if(ctl!=null){ctl.close();ctl=null;} } }
                if(!bound){ ctl=new DatagramSocket(0); dat=new DatagramSocket(ctl.getLocalPort()+1); }
                peer=InetAddress.getByName(host); peerCtl=5004; peerDat=5005;
            }
            ctl.setSoTimeout(400); dat.setSoTimeout(400);
            WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wm!=null){ wl=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"tenori-rtp"); wl.setReferenceCounted(false); wl.acquire(); }
            new Thread(ctlLoop,"rtp-ctl").start(); new Thread(datLoop,"rtp-dat").start();
            if(!listener) invite();
            return true;
        }catch(Exception e){ close(); return false; }
    }
    private void raw(byte[] b,int n,int port){ if(peer==null)return; try{ ctl.send(new DatagramPacket(b,n,peer,port)); }catch(IOException ignored){} }
    private void invite(){
        byte[] b=new byte[28]; b[0]=(byte)0xFF;b[1]=(byte)0xFF;b[2]='I';b[3]='N';
        p32(b,4,2); p32(b,8,token); p32(b,12,ssrc);
        byte[] nm="TenoriDroid".getBytes(); System.arraycopy(nm,0,b,16,nm.length); b[16+nm.length]=0;
        raw(b,16+nm.length+1,peerCtl);
    }
    private void okReply(){
        byte[] b=new byte[28]; b[0]=(byte)0xFF;b[1]=(byte)0xFF;b[2]='O';b[3]='K';
        p32(b,4,2); p32(b,8,token); p32(b,12,ssrc);
        byte[] nm="TenoriDroid".getBytes(); System.arraycopy(nm,0,b,16,nm.length); b[16+nm.length]=0;
        raw(b,16+nm.length+1,peerCtl);
    }
    private void ck(int cnt,long t1,long t2,long t3){
        byte[] b=new byte[36]; b[0]=(byte)0xFF;b[1]=(byte)0xFF;b[2]='C';b[3]='K';
        p32(b,4,ssrc); b[8]=(byte)cnt; p64(b,12,t1); p64(b,20,t2); p64(b,28,t3); raw(b,36,peerCtl);
    }
    private void rs(){ byte[] b=new byte[10]; b[0]=(byte)0xFF;b[1]=(byte)0xFF;b[2]='R';b[3]='S'; p32(b,4,ssrc); b[8]=(byte)(seq>>8); b[9]=(byte)seq; raw(b,10,peerCtl); }
    private final Runnable ctlLoop=new Runnable(){ public void run(){
        byte[] b=new byte[256];
        while(run){
            try{
                DatagramPacket p=new DatagramPacket(b,b.length); ctl.receive(p);
                if(p.getLength()>=4&&(b[0]&0xFF)==0xFF&&(b[1]&0xFF)==0xFF){
                    char c1=(char)b[2],c2=(char)b[3];
                    if(c1=='I'&&c2=='N'){ peer=p.getAddress(); peerCtl=p.getPort(); peerDat=peerCtl+1; token=g32(b,8); okReply(); ok=true; ck(0,t10k(t0),0,0); }
                    else if(c1=='O'&&c2=='K'){ ok=true; ck(0,t10k(t0),0,0); }
                    else if(c1=='N'&&c2=='O'){ ok=false; }
                    else if(c1=='B'&&c2=='Y'){ ok=false; }
                    else if(c1=='C'&&c2=='K'&&p.getLength()>=36){
                        int cnt=b[8]; long t1=g64(b,12),t2=g64(b,20);
                        if(cnt==0) ck(1,t1,t10k(t0),0);
                        else if(cnt==1){ long t3=t10k(t0); ck(2,t1,t2,t3); offset=((t2-t1)+(t2-t3))/2; }
                    }
                }
            }catch(SocketTimeoutException ignored){}catch(IOException e){ if(!run)break; }
            long n=SystemClock.uptimeMillis();
            if(ok&&n-lastSync>5000){ lastSync=n; ck(0,t10k(t0),0,0); }
            if(ok&&n-lastFb>1000){ lastFb=n; rs(); }
        }
    }};
    private final Runnable datLoop=new Runnable(){ public void run(){
        byte[] b=new byte[512];
        while(run){
            try{ DatagramPacket p=new DatagramPacket(b,b.length); dat.receive(p);
                if(peer==null){ peer=p.getAddress(); peerDat=p.getPort(); } parseRtp(b,p.getLength());
            }catch(SocketTimeoutException ignored){}catch(IOException e){ if(!run)break; }
        }
    }};
    private void parseRtp(byte[] b,int n){
        MidiHub.In in=hub.in; if(in==null||n<13||(b[0]&0xFF)!=0x80) return;
        int pos=12,b0=b[pos]&0xFF,len;
        if((b0&0x80)!=0){ if(n<14)return; len=((b0&0x0F)<<8)|(b[pos+1]&0xFF); pos+=2; } else { len=b0&0x0F; pos+=1; }
        int end=Math.min(pos+len,n),st=0;
        while(pos<end){
            int guard=0; while(pos<end){ int v=b[pos++]&0xFF; if((v&0x80)==0)break; if(++guard>4)break; }
            if(pos>=end)break;
            int v=b[pos]&0xFF; if((v&0x80)!=0){ st=v; pos++; }
            if(st==0)break;
            if(st>=0xF8){ in.midiIn(st,0,0); st=0; continue; }
            int nl=MidiHub.len(st),d1=0,d2=0;
            if(nl>=2){ if(pos>=end)break; d1=b[pos++]&0x7F; }
            if(nl>=3){ if(pos>=end)break; d2=b[pos++]&0x7F; }
            in.midiIn(st,d1,d2);
        }
    }
    public void send(byte[] m,int len){
        if(!ok||peer==null||len>3) return;
        synchronized(tx){
            int total=14+len;
            tx[0]=(byte)0x80; tx[1]=(byte)0x61; seq=(seq+1)&0xFFFF; tx[2]=(byte)(seq>>8); tx[3]=(byte)seq;
            p32(tx,4,(int)(t10k(t0)+offset)); p32(tx,8,ssrc);
            tx[12]=(byte)(len+1); tx[13]=0; System.arraycopy(m,0,tx,14,len);
            try{ dat.send(new DatagramPacket(tx,total,peer,peerDat)); }catch(IOException ignored){}
        }
    }
    public void close(){
        if(ok){ byte[] b=new byte[16]; b[0]=(byte)0xFF;b[1]=(byte)0xFF;b[2]='B';b[3]='Y'; p32(b,4,2); p32(b,8,token); p32(b,12,ssrc); raw(b,16,peerCtl); }
        ok=false; run=false;
        if(wl!=null){ try{wl.release();}catch(Exception ignored){} wl=null; }
        if(ctl!=null)ctl.close(); if(dat!=null)dat.close(); ctl=null; dat=null;
    }
}
