package com.hipster.tenoridroid;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.*;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String USB_PERM="com.hipster.tenoridroid.USB_PERM";
    private Engine eng; private NativeAudio ae; private GridView grid;
    private TextView bpmLbl,octLbl,stat,hint; private Button playBtn;
    private final Button[] lb=new Button[16],sb=new Button[8];
    private Spinner modeSp,lenSp,divSp,sndSp,chSp,scaleSp,rootSp;
    private SeekBar velSk,gateSk,cutSk,aSk,dSk,sSk,rSk,offSk;
    private ToggleButton muteT,soloT,kitT;
    private boolean syncing,fastAudio=false; private float ui=1f;
    private final Handler h=new Handler();
    private UsbManager um; private MidiHub.Port usbPort,sppPort,netPort;
    private BluetoothAdapter bt; private int tapN; private long tapLast;

    protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); hideBars();
        DisplayMetrics dm=getResources().getDisplayMetrics();
        float xd=dm.xdpi>40?dm.xdpi:160f, yd=dm.ydpi>40?dm.ydpi:160f;
        double diag=Math.sqrt(Math.pow(dm.widthPixels/xd,2)+Math.pow(dm.heightPixels/yd,2));
        if(diag>8.5&&dm.density<1.5f) ui=1.4f;
        eng=new Engine(); ae=NativeAudio.create(this,eng.bpm,fastAudio); ae.setMaster(eng.masterVol);
        eng.audio=ae; eng.start();
        eng.midi.in=new MidiHub.In(){ public void midiIn(int s,int d1,int d2){ eng.midiIn(s,d1,d2); } };
        um=(UsbManager)getSystemService(Context.USB_SERVICE); bt=BluetoothAdapter.getDefaultAdapter();
        File auto=new File(dir(),"autosave.json");
        if(auto.exists()) try{ readPreset(auto); }catch(Exception ignored){}
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFF0A0C10);
        HorizontalScrollView hs=new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(top()); root.addView(hs);
        LinearLayout mid=new LinearLayout(this); mid.setOrientation(LinearLayout.HORIZONTAL);
        grid=new GridView(this,eng);
        LinearLayout gw=new LinearLayout(this); gw.setGravity(Gravity.CENTER); gw.addView(grid,new LinearLayout.LayoutParams(-2,-2));
        mid.addView(gw,new LinearLayout.LayoutParams(0,-1,1f));
        ScrollView sv=new ScrollView(this); sv.addView(panel()); mid.addView(sv,new LinearLayout.LayoutParams(dp(300),-1));
        root.addView(mid,new LinearLayout.LayoutParams(-1,0,1f));
        HorizontalScrollView bs=new HorizontalScrollView(this); bs.setHorizontalScrollBarEnabled(false); bs.addView(bottom()); root.addView(bs);
        setContentView(root);
        registerReceiver(usbRec,filter()); syncLayer(); refreshLayers(); refreshScenes(); h.post(tick); autoUsb();
    }
    private void hideBars(){ if(Build.VERSION.SDK_INT>=19) getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY); }
    private File dir(){ File f=getExternalFilesDir(null); return f!=null?f:getFilesDir(); }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density*ui); }
    private LinearLayout row(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setPadding(0,dp(2),0,dp(2)); return l; }
    private TextView lbl(String s){ TextView t=new TextView(this); t.setText(s); t.setTextSize(10*ui); t.setTextColor(0xFF8FA3B8); t.setPadding(dp(4),dp(4),dp(4),0); return t; }
    private Button btn(String s,View.OnClickListener l){ Button b=new Button(this); b.setText(s); b.setTextSize(10*ui);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(6),dp(6),dp(6),dp(6)); b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2,-2)); return b; }
    private ToggleButton tog(String s,boolean i,CompoundButton.OnCheckedChangeListener l){ ToggleButton t=new ToggleButton(this);
        t.setText(s); t.setTextOn(s); t.setTextOff(s); t.setTextSize(9*ui); t.setChecked(i); t.setMinHeight(0); t.setMinimumHeight(0);
        t.setPadding(dp(4),dp(6),dp(4),dp(6)); t.setOnCheckedChangeListener(l); t.setLayoutParams(new LinearLayout.LayoutParams(-2,-2)); return t; }
    private SeekBar seek(int max,int val,int w,SeekBar.OnSeekBarChangeListener l){ SeekBar s=new SeekBar(this);
        s.setMax(max); s.setProgress(val); s.setOnSeekBarChangeListener(l); s.setLayoutParams(new LinearLayout.LayoutParams(dp(w),-2)); return s; }
    private Spinner spin(String[] it,int sel,AdapterView.OnItemSelectedListener l){ Spinner s=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,it);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setSelection(sel);
        s.setOnItemSelectedListener(l); s.setLayoutParams(new LinearLayout.LayoutParams(-2,-2)); return s; }
    private abstract static class Seek implements SeekBar.OnSeekBarChangeListener{
        public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){} }
    private abstract static class Pick implements AdapterView.OnItemSelectedListener{ public void onNothingSelected(AdapterView<?> p){} }

    private View top(){
        LinearLayout r=row(); r.setPadding(dp(6),dp(4),dp(6),dp(4));
        playBtn=btn("PLAY",new View.OnClickListener(){ public void onClick(View v){
            if(eng.playing){eng.stopPlay();playBtn.setText("PLAY");} else {eng.play();playBtn.setText("STOP");} }});
        r.addView(playBtn);
        r.addView(btn("-",new View.OnClickListener(){ public void onClick(View v){ setBpm(eng.bpm-1); }}));
        bpmLbl=new TextView(this); bpmLbl.setTextSize(16*ui); bpmLbl.setTextColor(0xFF39D0FF);
        bpmLbl.setPadding(dp(8),0,dp(8),0); bpmLbl.setText(eng.bpm+" BPM"); r.addView(bpmLbl);
        r.addView(btn("+",new View.OnClickListener(){ public void onClick(View v){ setBpm(eng.bpm+1); }}));
        r.addView(btn("TAP",new View.OnClickListener(){ public void onClick(View v){
            long n=SystemClock.uptimeMillis();
            if(n-tapLast<2200&&tapN>0){ setBpm(Math.max(30,Math.min(300,(int)(60000L/Math.max(120,(n-tapLast)))))); tapN=0; } else tapN++;
            tapLast=n; }}));
        r.addView(lbl("SWING"));
        r.addView(seek(80,eng.swing,110,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.swing=p; }}));
        r.addView(lbl("VOL"));
        r.addView(seek(100,eng.masterVol,100,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u){eng.masterVol=p;ae.setMaster(p);} }}));
        r.addView(btn("PANIC",new View.OnClickListener(){ public void onClick(View v){ eng.panic(); toast("All notes off"); }}));
        r.addView(btn("SAVE",new View.OnClickListener(){ public void onClick(View v){ doSave(); }}));
        r.addView(btn("LOAD",new View.OnClickListener(){ public void onClick(View v){ doLoad(); }}));
        stat=new TextView(this); stat.setTextSize(10*ui); stat.setTextColor(0xFF6FE38A); stat.setPadding(dp(10),dp(6),dp(6),0); r.addView(stat);
        return r;
    }
    private void setBpm(int b){ eng.bpm=Math.max(30,Math.min(300,b)); bpmLbl.setText(eng.bpm+" BPM"); ae.setBpm(eng.bpm); }

    private View panel(){
        LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(6),dp(2),dp(6),dp(8));
        p.addView(lbl("LAYERS (долгий тап = mute)"));
        for(int rr=0;rr<2;rr++){ LinearLayout r=row();
            for(int k=0;k<8;k++){ final int i=rr*8+k;
                Button b=btn(String.valueOf(i+1),new View.OnClickListener(){ public void onClick(View v){ eng.sel=i; syncLayer(); refreshLayers(); }});
                b.setOnLongClickListener(new View.OnLongClickListener(){ public boolean onLongClick(View v){
                    eng.layers[i].mute=!eng.layers[i].mute; refreshLayers(); syncLayer(); return true; }});
                b.setLayoutParams(new LinearLayout.LayoutParams(dp(34),dp(34))); lb[i]=b; r.addView(b); }
            p.addView(r); }
        LinearLayout r1=row();
        muteT=tog("MUTE",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ if(!syncing){eng.layers[eng.sel].mute=c;refreshLayers();} }});
        soloT=tog("SOLO",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ if(!syncing)eng.layers[eng.sel].solo=c; }});
        kitT=tog("KIT",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ if(!syncing)eng.layers[eng.sel].kit=c; }});
        r1.addView(muteT); r1.addView(soloT); r1.addView(kitT);
        r1.addView(btn("CLR",new View.OnClickListener(){ public void onClick(View v){ eng.layers[eng.sel].clear(); grid.invalidate(); }}));
        r1.addView(btn("RND",new View.OnClickListener(){ public void onClick(View v){ eng.randomize(eng.sel,12); grid.invalidate(); }}));
        p.addView(r1);
        p.addView(lbl("MODE / LEN / DIV"));
        LinearLayout r2=row();
        modeSp=spin(Layer.MODES,0,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing){Layer l=eng.layers[eng.sel];l.mode=i;l.pos=-1;l.dir=1;} }});
        String[] lens=new String[16]; for(int i=0;i<16;i++)lens[i]=String.valueOf(i+1);
        lenSp=spin(lens,15,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing)eng.layers[eng.sel].len=i+1; }});
        String[] dv=new String[Layer.DIVS.length]; for(int i=0;i<dv.length;i++)dv[i]="1/"+Layer.DIVS[i];
        divSp=spin(dv,0,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing)eng.layers[eng.sel].div=Layer.DIVS[i]; }});
        r2.addView(modeSp); r2.addView(lenSp); r2.addView(divSp); p.addView(r2);
        p.addView(lbl("SOUND"));
        sndSp=spin(Engine.SOUNDS,0,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing)eng.layers[eng.sel].sound=i; }});
        p.addView(sndSp);
        LinearLayout r3=row();
        r3.addView(btn("OCT-",new View.OnClickListener(){ public void onClick(View v){ Layer l=eng.layers[eng.sel]; l.octave=Math.max(-1,l.octave-1); octLbl.setText("O"+l.octave); }}));
        octLbl=new TextView(this); octLbl.setTextColor(Color.WHITE); octLbl.setTextSize(12*ui); octLbl.setPadding(dp(6),dp(6),dp(6),0); r3.addView(octLbl);
        r3.addView(btn("OCT+",new View.OnClickListener(){ public void onClick(View v){ Layer l=eng.layers[eng.sel]; l.octave=Math.min(8,l.octave+1); octLbl.setText("O"+l.octave); }}));
        p.addView(r3);
        p.addView(lbl("VEL / GATE / CUTOFF"));
        velSk=seek(127,100,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].velocity=Math.max(1,p); }});
        gateSk=seek(200,60,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].gate=Math.max(3,p); }});
        cutSk=seek(100,62,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].cutoff=p/100f; }});
        p.addView(velSk); p.addView(gateSk); p.addView(cutSk);
        p.addView(lbl("ADSR"));
        aSk=seek(100,1,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].attack=0.001f+p*0.01f; }});
        dSk=seek(100,25,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].decay=0.01f+p*0.02f; }});
        sSk=seek(100,35,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].sustain=p/100f; }});
        rSk=seek(100,12,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u)eng.layers[eng.sel].release=0.01f+p*0.02f; }});
        p.addView(aSk); p.addView(dSk); p.addView(sSk); p.addView(rSk);
        p.addView(lbl("MIDI CHANNEL"));
        String[] ch=new String[16]; for(int i=0;i<16;i++)ch[i]="CH "+(i+1);
        chSp=spin(ch,0,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing)eng.layers[eng.sel].channel=i; }});
        LinearLayout r4=row(); r4.addView(chSp);
        r4.addView(btn("PC-",new View.OnClickListener(){ public void onClick(View v){ pc(-1); }}));
        r4.addView(btn("PC+",new View.OnClickListener(){ public void onClick(View v){ pc(1); }}));
        r4.addView(btn("SEND PC",new View.OnClickListener(){ public void onClick(View v){ eng.sendPC(eng.sel); }}));
        p.addView(r4);
        p.addView(lbl("SCALE / ROOT"));
        LinearLayout r5=row();
        scaleSp=spin(Scale.NAMES,eng.scale,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing){eng.scale=i;grid.invalidate();} }});
        rootSp=spin(Scale.ROOTS,eng.root,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ if(!syncing)eng.root=i; }});
        r5.addView(scaleSp); r5.addView(rootSp); p.addView(r5);
        p.addView(lbl("SCENES (тап = вкл, долгий = записать)"));
        LinearLayout r6=row();
        for(int i=0;i<8;i++){ final int k=i;
            Button b=btn(String.valueOf((char)('A'+i)),new View.OnClickListener(){ public void onClick(View v){ eng.loadScene(k); refreshScenes(); grid.invalidate(); }});
            b.setOnLongClickListener(new View.OnLongClickListener(){ public boolean onLongClick(View v){ eng.saveScene(k); refreshScenes(); toast("Scene saved"); return true; }});
            b.setLayoutParams(new LinearLayout.LayoutParams(dp(32),dp(34))); sb[i]=b; r6.addView(b); }
        p.addView(r6);
        LinearLayout r7=row();
        r7.addView(tog("SONG",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.songOn=c; eng.songPos=0; }}));
        r7.addView(lbl("LEN"));
        r7.addView(spin(new String[]{"1","2","3","4","5","6","7","8"},eng.songLen-1,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ eng.songLen=i+1; }}));
        r7.addView(lbl("BARS"));
        r7.addView(spin(new String[]{"1","2","4","8"},0,new Pick(){ public void onItemSelected(AdapterView<?> a,View v,int i,long id){ eng.barsPerScene=new int[]{1,2,4,8}[i]; }}));
        p.addView(r7);
        p.addView(lbl("MIDI ПОРТЫ"));
        LinearLayout r8=row();
        r8.addView(btn("USB...",new View.OnClickListener(){ public void onClick(View v){ pickUsb(); }}));
        r8.addView(btn("BT-SPP...",new View.OnClickListener(){ public void onClick(View v){ pickSpp(); }}));
        r8.addView(btn("NET...",new View.OnClickListener(){ public void onClick(View v){ pickNet(); }}));
        r8.addView(btn("CLOSE",new View.OnClickListener(){ public void onClick(View v){ eng.midi.closeAll(); usbPort=null;sppPort=null;netPort=null; toast("Порты закрыты"); }}));
        p.addView(r8);
        LinearLayout r9=row();
        r9.addView(tog("MIDI",true,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.midiOn=c; }}));
        r9.addView(tog("CLK OUT",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.clockOut=c; }}));
        r9.addView(tog("EXT SYNC",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.extSync=c; }}));
        r9.addView(tog("THRU",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.thru=c; }}));
        p.addView(r9);
        LinearLayout r10=row();
        r10.addView(tog("AUDIO",true,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.audioOn=c; }}));
        r10.addView(tog("REC-IN",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ eng.record=c; }}));
        r10.addView(btn("DIAG",new View.OnClickListener(){ public void onClick(View v){ diag(); }}));
        r10.addView(tog("FAST AUD",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){
            fastAudio=c; if(eng.playing){eng.stopPlay();playBtn.setText("PLAY");}
            eng.audio=null; ae.release(); ae=NativeAudio.create(MainActivity.this,eng.bpm,fastAudio);
            ae.setMaster(eng.masterVol); eng.audio=ae; toast("Backend: "+ae.name()); }}));
        p.addView(r10);
        p.addView(lbl("MIDI OFFSET, мс"));
        offSk=seek(400,eng.midiOffsetMs+200,260,new Seek(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if(u){
            eng.midiOffsetMs=p-200; hint.setText("offset "+eng.midiOffsetMs+" ms | audio ~"+ae.latencyMs()+" ms"); } }});
        p.addView(offSk);
        return p;
    }
    private void pc(int d){ Layer l=eng.layers[eng.sel]; l.program=Math.max(-1,Math.min(127,l.program+d)); if(l.program>=0)eng.sendPC(eng.sel); toast("PC "+l.program); }
    private View bottom(){
        LinearLayout r=row(); r.setPadding(dp(6),0,dp(6),dp(2));
        r.addView(tog("ACCENT",false,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ grid.accent=c; }}));
        r.addView(tog("GHOST",true,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ grid.ghost=c; }}));
        r.addView(tog("AUDITION",true,new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b,boolean c){ grid.audition=c; }}));
        hint=new TextView(this); hint.setTextSize(10*ui); hint.setTextColor(0xFF7A8CA0); hint.setPadding(dp(12),dp(8),dp(6),0);
        hint.setText("Тап = точка, свайп = рисование. X = время, Y = высота. offset "+eng.midiOffsetMs+" ms | audio ~"+ae.latencyMs()+" ms");
        r.addView(hint); return r;
    }
    private void syncLayer(){
        syncing=true; Layer l=eng.layers[eng.sel];
        modeSp.setSelection(l.mode); lenSp.setSelection(Math.max(0,Math.min(15,l.len-1)));
        int di=0; for(int i=0;i<Layer.DIVS.length;i++) if(Layer.DIVS[i]==l.div)di=i;
        divSp.setSelection(di); sndSp.setSelection(Math.max(0,Math.min(15,l.sound))); chSp.setSelection(Math.max(0,Math.min(15,l.channel)));
        velSk.setProgress(l.velocity); gateSk.setProgress(l.gate); cutSk.setProgress((int)(l.cutoff*100));
        aSk.setProgress((int)((l.attack-0.001f)/0.01f)); dSk.setProgress((int)((l.decay-0.01f)/0.02f));
        sSk.setProgress((int)(l.sustain*100)); rSk.setProgress((int)((l.release-0.01f)/0.02f));
        muteT.setChecked(l.mute); soloT.setChecked(l.solo); kitT.setChecked(l.kit); octLbl.setText("O"+l.octave);
        scaleSp.setSelection(eng.scale); rootSp.setSelection(eng.root); syncing=false; grid.invalidate();
    }
    private void refreshLayers(){ for(int i=0;i<16;i++){ Layer l=eng.layers[i];
        lb[i].setBackgroundColor(l.mute?0xFF2A2F38:(i==eng.sel?0xFF39D0FF:0xFF1B2430));
        lb[i].setTextColor(l.mute?0xFF55606E:(i==eng.sel?0xFF001018:Color.WHITE)); } }
    private void refreshScenes(){ for(int i=0;i<8;i++) sb[i].setBackgroundColor(i==eng.scene?0xFFFFD24A:0xFF1B2430); }
    private final Runnable tick=new Runnable(){ public void run(){ stat.setText(eng.midi.status()+"  x"+ae.xruns()); refreshScenes(); h.postDelayed(tick,500); }};

    private IntentFilter filter(){ IntentFilter f=new IntentFilter(USB_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); return f; }
    private final BroadcastReceiver usbRec=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        String a=i.getAction(); UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if(USB_PERM.equals(a)){ if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) openUsb(d); else toast("Нет доступа к USB"); }
        else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)){ if(d!=null&&UsbMidi.find(um).contains(d)) askUsb(d); }
        else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)){ if(usbPort!=null){ eng.midi.ports.remove(usbPort); usbPort.close(); usbPort=null; toast("USB отключен"); } }
    }};
    private void autoUsb(){ List<UsbDevice> l=UsbMidi.find(um); if(!l.isEmpty()) askUsb(l.get(0)); }
    private void askUsb(UsbDevice d){ if(um.hasPermission(d)) openUsb(d);
        else um.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(USB_PERM),0)); }
    private void openUsb(UsbDevice d){
        if(d==null)return;
        if(usbPort!=null){ eng.midi.ports.remove(usbPort); usbPort.close(); }
        String nm=d.getDeviceName(); UsbMidi u=new UsbMidi(eng.midi,nm.substring(nm.lastIndexOf('/')+1));
        if(u.open(um,d)){ usbPort=u; eng.midi.ports.add(u); toast("USB MIDI подключен"); } else toast("Не MIDI-устройство");
    }
    private void pickUsb(){
        final List<UsbDevice> l=UsbMidi.find(um);
        if(l.isEmpty()){ toast("USB MIDI не найден. OTG + powered hub?"); return; }
        String[] n=new String[l.size()]; for(int i=0;i<l.size();i++) n[i]=l.get(i).getDeviceName();
        new AlertDialog.Builder(this).setTitle("USB MIDI").setItems(n,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int i){ askUsb(l.get(i)); }}).show();
    }
    private void pickSpp(){
        if(bt==null){ toast("Bluetooth нет"); return; }
        final List<BluetoothDevice> l=new ArrayList<BluetoothDevice>(bt.getBondedDevices());
        if(l.isEmpty()){ toast("Сначала спарь устройство в настройках Android"); return; }
        String[] n=new String[l.size()]; for(int i=0;i<l.size();i++){ String s=l.get(i).getName(); n[i]=s==null?"unknown":s; }
        new AlertDialog.Builder(this).setTitle("BT Serial MIDI").setItems(n,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,final int i){ new Thread(new Runnable(){ public void run(){
                if(sppPort!=null){ eng.midi.ports.remove(sppPort); sppPort.close(); }
                String s=l.get(i).getName(); final SppMidi p=new SppMidi(eng.midi,s==null?"unknown":s);
                final boolean ok=p.connect(l.get(i)); if(ok){ sppPort=p; eng.midi.ports.add(p); }
                h.post(new Runnable(){ public void run(){ toast(ok?"SPP MIDI подключен":"SPP: не удалось"); }});
            }}).start(); }}).show();
    }
    private void pickNet(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(12),dp(8),dp(12),0);
        final EditText ip=new EditText(this); ip.setHint("IP компа, напр. 192.168.1.50"); ip.setText("192.168.1.50");
        final CheckBox lst=new CheckBox(this); lst.setText("Слушать (ПК сам подключится к планшету)"); box.addView(ip); box.addView(lst);
        String myIp="?";
        try{ WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            myIp=Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress()); }catch(Exception ignored){}
        new AlertDialog.Builder(this).setTitle("RTP-MIDI по Wi-Fi").setView(box)
            .setMessage("Мой IP: "+myIp+" | ПК: rtpMIDI (Win) / Network MIDI (macOS) / rtpmidid (Linux)")
            .setPositiveButton("Пуск",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int i){
                if(netPort!=null){ eng.midi.ports.remove(netPort); netPort.close(); }
                RtpMidi p=new RtpMidi(eng.midi,lst.isChecked()?"listen":ip.getText().toString());
                if(p.start(MainActivity.this,ip.getText().toString().trim(),lst.isChecked())){ netPort=p; eng.midi.ports.add(p);
                    toast(lst.isChecked()?"Жду подключение 5004/5005":"Приглашение отправлено"); } else toast("Сеть: сокеты не открылись");
            }}).show();
    }
    private void diag(){
        String nl=String.valueOf((char)10); StringBuilder s=new StringBuilder();
        s.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" Android ").append(Build.VERSION.RELEASE)
         .append(" API ").append(Build.VERSION.SDK_INT).append(" ABI ").append(Build.CPU_ABI).append(nl);
        boolean host=getPackageManager().hasSystemFeature("android.hardware.usb.host");
        s.append("USB HOST: ").append(host?"ЕСТЬ":"НЕТ").append(nl);
        s.append("USB MIDI найдено: ").append(UsbMidi.find(um).size()).append(nl);
        s.append("Backend: ").append(ae.name()).append(nl);
        s.append("Latency ~").append(ae.latencyMs()).append(" ms, XRUNS=").append(ae.xruns()).append(nl);
        s.append(host?"USB или NET - твои транспорты.":"OTG нет - работай через NET (Wi-Fi).");
        new AlertDialog.Builder(this).setTitle("Диагностика").setMessage(s.toString()).setPositiveButton("OK",null).show();
    }
    private void doSave(){
        final EditText et=new EditText(this); et.setHint("имя паттерна");
        new AlertDialog.Builder(this).setTitle("Сохранить").setView(et)
            .setPositiveButton("OK",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int i){
                String n=et.getText().toString().trim(); if(n.isEmpty())n="pattern";
                try{ writePreset(new File(dir(),n+".json")); toast("Сохранено: "+n); }catch(Exception e){ toast("Ошибка: "+e); }
            }}).show();
    }
    private void doLoad(){
        final List<File> js=new ArrayList<File>(); File[] fs=dir().listFiles();
        if(fs!=null) for(File f:fs) if(f.getName().endsWith(".json")) js.add(f);
        if(js.isEmpty()){ toast("Нет сохранений"); return; }
        String[] n=new String[js.size()]; for(int i=0;i<js.size();i++)n[i]=js.get(i).getName();
        new AlertDialog.Builder(this).setTitle("Загрузить").setItems(n,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int i){ try{ readPreset(js.get(i)); setBpm(eng.bpm); syncLayer(); refreshLayers(); toast("Загружено"); }
                catch(Exception e){ toast("Ошибка: "+e); } }}).show();
    }
    private void writePreset(File f) throws Exception {
        JSONObject o=new JSONObject(); o.put("bpm",eng.bpm); o.put("swing",eng.swing); o.put("scale",eng.scale);
        o.put("root",eng.root); o.put("off",eng.midiOffsetMs); o.put("vol",eng.masterVol);
        o.put("songLen",eng.songLen); o.put("bars",eng.barsPerScene);
        JSONArray ls=new JSONArray();
        for(Layer l:eng.layers){ JSONObject j=new JSONObject(); j.put("g",enc(l.grid));
            j.put("snd",l.sound);j.put("ch",l.channel);j.put("pc",l.program);j.put("oct",l.octave);
            j.put("vel",l.velocity);j.put("gate",l.gate);j.put("mode",l.mode);j.put("len",l.len);j.put("div",l.div);
            j.put("kit",l.kit);j.put("mute",l.mute);j.put("cut",l.cutoff);j.put("a",l.attack);j.put("d",l.decay);
            j.put("s",l.sustain);j.put("r",l.release); ls.put(j); }
        o.put("layers",ls);
        JSONArray sc=new JSONArray(); for(byte[] x:eng.scenes) sc.put(enc(x)); o.put("scenes",sc);
        Writer w=new FileWriter(f); w.write(o.toString()); w.close();
    }
    private void readPreset(File f) throws Exception {
        BufferedReader r=new BufferedReader(new FileReader(f)); StringBuilder sb=new StringBuilder(); String line;
        while((line=r.readLine())!=null) sb.append(line); r.close();
        JSONObject o=new JSONObject(sb.toString());
        eng.bpm=o.optInt("bpm",120); eng.swing=o.optInt("swing",0); eng.scale=o.optInt("scale",1); eng.root=o.optInt("root",0);
        eng.midiOffsetMs=o.optInt("off",70); eng.masterVol=o.optInt("vol",85); eng.songLen=o.optInt("songLen",4); eng.barsPerScene=o.optInt("bars",1);
        JSONArray ls=o.getJSONArray("layers");
        for(int i=0;i<16&&i<ls.length();i++){ JSONObject j=ls.getJSONObject(i); Layer l=eng.layers[i]; dec(j.getString("g"),l.grid);
            l.sound=j.optInt("snd");l.channel=j.optInt("ch");l.program=j.optInt("pc",-1);l.octave=j.optInt("oct",3);
            l.velocity=j.optInt("vel",100);l.gate=j.optInt("gate",60);l.mode=j.optInt("mode");l.len=j.optInt("len",16);l.div=j.optInt("div",1);
            l.kit=j.optBoolean("kit");l.mute=j.optBoolean("mute");l.cutoff=(float)j.optDouble("cut",0.62);
            l.attack=(float)j.optDouble("a",0.004);l.decay=(float)j.optDouble("d",0.25);l.sustain=(float)j.optDouble("s",0.35);l.release=(float)j.optDouble("r",0.12); }
        JSONArray sc=o.optJSONArray("scenes");
        if(sc!=null) for(int i=0;i<8&&i<sc.length();i++) dec(sc.getString(i),eng.scenes[i]);
        ae.setBpm(eng.bpm); ae.setMaster(eng.masterVol);
    }
    private static String enc(byte[] g){ StringBuilder s=new StringBuilder(g.length); for(byte b:g) s.append((char)('0'+b)); return s.toString(); }
    private static void dec(String s,byte[] g){ for(int i=0;i<g.length&&i<s.length();i++) g[i]=(byte)(s.charAt(i)-'0'); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
    protected void onPause(){ super.onPause(); try{ writePreset(new File(dir(),"autosave.json")); }catch(Exception ignored){}
        eng.stopPlay(); if(playBtn!=null)playBtn.setText("PLAY"); }
    protected void onDestroy(){ super.onDestroy(); h.removeCallbacksAndMessages(null);
        try{ unregisterReceiver(usbRec); }catch(Exception ignored){}
        if(netPort!=null)netPort.close(); eng.shutdown(); eng.midi.closeAll(); ae.release(); }
    public void onWindowFocusChanged(boolean f){ super.onWindowFocusChanged(f); if(f)hideBars(); }
}
