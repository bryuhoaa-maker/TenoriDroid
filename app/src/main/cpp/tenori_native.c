#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>
#include <android/log.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>
#define TAG "tenori-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define MAXV 20
#define MAXEV 128
#define MAXBUF 4
#define MAXBLOCK 1024
typedef struct { int on,wave,stg,delay,gate,hp; float env,amp,sus,aInc,dInc,rInc,ph,inc,f0,peCur,peMul,nmix,lp,coef,fBase,fK; unsigned ns; } Voice;
typedef struct { int note,vel,snd,delay,gate; float cut,atk,dec,sus,rel; } Ev;
static Voice V[MAXV]; static Ev Q[MAXEV]; static volatile int qh=0,qt=0,spin=0;
static float sine[1025], mixb[MAXBLOCK]; static int16_t *pcm[MAXBUF];
static float *dly=NULL; static int dlen=1024,dpos=0,dmax=0;
static float gMaster=0.85f,gWet=0.16f,gFb=0.32f;
static int gSr=44100,gBlock=256,gNBuf=3,gIdx=0,gRR=0;
static volatile int64_t gFrames=0,gAnchor=0; static volatile int gPanic=0,gXrun=0; static int64_t gLastCb=0;
static SLObjectItf engObj=NULL,mixObj=NULL,plyObj=NULL; static SLEngineItf eng=NULL;
static SLPlayItf playItf=NULL; static SLAndroidSimpleBufferQueueItf bq=NULL;
static inline void lock(void){ while(__sync_lock_test_and_set(&spin,1)){} }
static inline void unlock(void){ __sync_lock_release(&spin); }
static inline int64_t nowNs(void){ struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (int64_t)t.tv_sec*1000000000LL+t.tv_nsec; }
static inline float tab(float p){ p-=(float)(int)p; if(p<0.f)p+=1.f; float f=p*1024.f; int i=(int)f; float fr=f-(float)i; return sine[i]+(sine[i+1]-sine[i])*fr; }
static inline float ex(float t){ return expf(-1.f/((float)gSr*t)); }
static void vset(Voice *v,const Ev *e){
    memset(v,0,sizeof(*v)); v->on=1; v->delay=e->delay; v->gate=e->gate;
    v->ns=0x2545f491u^(unsigned)(e->note*977u)^(unsigned)nowNs(); if(!v->ns)v->ns=1u;
    v->amp=(e->vel/127.f)*0.24f;
    float f0=440.f*powf(2.f,(e->note-69)/12.f), atk=e->atk,dec=e->dec,sus=e->sus,rel=e->rel,cut=e->cut;
    int wave=0,hp=0; float nmix=0.f,peCur=0.f,peMul=0.f;
    switch(e->snd){
        case 1: wave=1; break;
        case 2: wave=2; break;
        case 3: wave=3; break;
        case 4: wave=4; if(dec<0.5f)dec=0.5f; sus=0.f; break;
        case 5: nmix=1.f; break;
        case 6: wave=0;f0=47.f;peCur=130.f;peMul=ex(0.022f);atk=0.001f;dec=0.30f;sus=0.f;rel=0.04f;cut=0.5f; break;
        case 7: wave=0;f0=190.f;nmix=0.72f;hp=1;atk=0.001f;dec=0.16f;sus=0.f;rel=0.05f;cut=0.75f; break;
        case 8: nmix=1.f;hp=1;atk=0.001f;dec=0.045f;sus=0.f;rel=0.02f;cut=0.95f; break;
        case 9: nmix=1.f;hp=1;atk=0.001f;dec=0.32f;sus=0.f;rel=0.08f;cut=0.92f; break;
        case 10: nmix=1.f;hp=1;atk=0.003f;dec=0.18f;sus=0.f;rel=0.06f;cut=0.80f; break;
        case 11: wave=0;f0=150.f;peCur=70.f;peMul=ex(0.05f);atk=0.002f;dec=0.30f;sus=0.f;rel=0.05f; break;
        case 12: wave=3;f0=1750.f;atk=0.001f;dec=0.03f;sus=0.f;rel=0.01f;cut=0.95f; break;
        case 13: wave=3;f0=560.f;nmix=0.25f;atk=0.001f;dec=0.25f;sus=0.f;rel=0.05f;cut=0.85f; break;
        case 14: wave=0;f0*=0.5f;atk=0.006f;if(dec<0.3f)dec=0.3f;cut=0.35f; break;
        case 15: wave=2;atk=0.002f;dec=0.16f;sus=0.f;rel=0.06f;if(cut>0.7f)cut=0.7f; break;
        default: break;
    }
    if(e->snd>=6) v->gate=gSr;
    v->wave=wave; v->hp=hp; v->nmix=nmix; v->f0=f0; v->peCur=peCur; v->peMul=peMul; v->sus=sus;
    v->aInc=1.f/fmaxf(1.f,atk*gSr); v->dInc=(1.f-sus)/fmaxf(1.f,dec*gSr);
    v->rInc=fmaxf(0.00002f,1.f/fmaxf(1.f,rel*gSr));
    v->fBase=60.f*powf(2.f,cut*7.1f); v->fK=(float)gSr/6.2832f;
    v->inc=fminf(0.45f,f0/(float)gSr); v->coef=0.5f;
}
static int allocVoice(void){ for(int i=0;i<MAXV;i++){int k=(gRR+i)%MAXV; if(!V[k].on){gRR=(k+1)%MAXV;return k;}} int k=gRR; gRR=(gRR+1)%MAXV; return k; }
static void render(int16_t *out,int n){
    Ev tmp[16]; int ne=0;
    lock(); while(qt!=qh&&ne<16){ tmp[ne++]=Q[qt]; qt=(qt+1)%MAXEV; } unlock();
    for(int i=0;i<ne;i++) vset(&V[allocVoice()],&tmp[i]);
    if(gPanic){ gPanic=0; for(int i=0;i<MAXV;i++) if(V[i].on) V[i].stg=3; }
    memset(mixb,0,sizeof(float)*n);
    for(int k=0;k<MAXV;k++){
        Voice *x=&V[k]; if(!x->on) continue; int d=x->delay;
        for(int i=0;i<n;i++){
            if(d>0){ d--; continue; }
            float e=x->env;
            if(x->stg==0){ e+=x->aInc; if(e>=1.f){e=1.f;x->stg=1;} }
            else if(x->stg==1){ e-=x->dInc; if(e<=x->sus){e=x->sus;x->stg=x->sus<=0.001f?3:2;} }
            else if(x->stg==3){ e-=x->rInc; if(e<=0.f){x->on=0;x->env=0.f;break;} }
            if(--x->gate<=0&&x->stg<3) x->stg=3;
            x->env=e;
            if((i&7)==0){ float f=x->fBase*(0.15f+0.85f*e); x->coef=f/(f+x->fK); }
            float inc=x->inc;
            if(x->peCur>0.01f){ x->peCur*=x->peMul; inc=(x->f0+x->peCur)/(float)gSr; }
            float ph=x->ph,o;
            switch(x->wave){
                case 1: o=ph<0.5f?4.f*ph-1.f:3.f-4.f*ph; break;
                case 2: o=2.f*ph-1.f; break;
                case 3: o=ph<0.5f?1.f:-1.f; break;
                case 4: o=tab(ph+0.35f*tab(ph*2.f)); break;
                default: o=tab(ph);
            }
            ph+=inc; if(ph>=1.f)ph-=1.f; x->ph=ph;
            float s=o;
            if(x->nmix>0.f){ x->ns^=x->ns<<13; x->ns^=x->ns>>17; x->ns^=x->ns<<5; float nz=(float)((int)x->ns)*(1.f/2147483648.f); s=o*(1.f-x->nmix)+nz*x->nmix; }
            x->lp+=x->coef*(s-x->lp); s=x->hp?(s-x->lp):x->lp;
            mixb[i]+=s*e*x->amp;
        }
        x->delay=d;
    }
    for(int i=0;i<n;i++){
        float dd=dly[dpos],s=mixb[i]; dly[dpos]=s+dd*gFb;
        if(++dpos>=dlen)dpos=0;
        float o=(s+dd*gWet)*gMaster; if(o>1.f)o=1.f; else if(o<-1.f)o=-1.f;
        out[i]=(int16_t)(o*32000.f);
    }
}
static void bqcb(SLAndroidSimpleBufferQueueItf q,void *ctx){
    int64_t t=nowNs();
    if(gLastCb){ int64_t e=(int64_t)gBlock*1000000000LL/gSr; if(t-gLastCb>e*2) gXrun++; }
    gLastCb=t; int idx=gIdx; render(pcm[idx],gBlock);
    (*q)->Enqueue(q,pcm[idx],(SLuint32)(gBlock*2));
    gIdx=(gIdx+1)%gNBuf; lock(); gFrames+=gBlock; gAnchor=t; unlock();
}
static void slDestroy(void){
    if(playItf){ (*playItf)->SetPlayState(playItf,SL_PLAYSTATE_STOPPED); playItf=NULL; }
    if(plyObj){ (*plyObj)->Destroy(plyObj); plyObj=NULL; }
    if(mixObj){ (*mixObj)->Destroy(mixObj); mixObj=NULL; }
    if(engObj){ (*engObj)->Destroy(engObj); engObj=NULL; }
    eng=NULL; bq=NULL;
}
static int slInit(void){
    if(slCreateEngine(&engObj,0,NULL,0,NULL,NULL)!=SL_RESULT_SUCCESS) return -1;
    if((*engObj)->Realize(engObj,SL_BOOLEAN_FALSE)!=SL_RESULT_SUCCESS) return -2;
    (*engObj)->GetInterface(engObj,SL_IID_ENGINE,&eng);
    (*eng)->CreateOutputMix(eng,&mixObj,0,NULL,NULL); (*mixObj)->Realize(mixObj,SL_BOOLEAN_FALSE);
    SLDataLocator_AndroidSimpleBufferQueue loc={SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,(SLuint32)gNBuf};
    SLDataFormat_PCM fmt; fmt.formatType=SL_DATAFORMAT_PCM; fmt.numChannels=1;
    fmt.samplesPerSec=(SLuint32)gSr*1000; fmt.bitsPerSample=SL_PCMSAMPLEFORMAT_FIXED_16;
    fmt.containerSize=SL_PCMSAMPLEFORMAT_FIXED_16; fmt.channelMask=SL_SPEAKER_FRONT_CENTER; fmt.endianness=SL_BYTEORDER_LITTLEENDIAN;
    SLDataSource src={&loc,&fmt}; SLDataLocator_OutputMix ol={SL_DATALOCATOR_OUTPUTMIX,mixObj}; SLDataSink snk={&ol,NULL};
    const SLInterfaceID ids[3]={SL_IID_BUFFERQUEUE,SL_IID_ANDROIDCONFIGURATION,SL_IID_VOLUME};
    const SLboolean req[3]={SL_BOOLEAN_TRUE,SL_BOOLEAN_FALSE,SL_BOOLEAN_FALSE};
    if((*eng)->CreateAudioPlayer(eng,&plyObj,&src,&snk,3,ids,req)!=SL_RESULT_SUCCESS) return -3;
    SLAndroidConfigurationItf cfg=NULL;
    if((*plyObj)->GetInterface(plyObj,SL_IID_ANDROIDCONFIGURATION,&cfg)==SL_RESULT_SUCCESS&&cfg){
        SLuint32 mode=SL_ANDROID_PERFORMANCE_LATENCY;
        (*cfg)->SetConfiguration(cfg,SL_ANDROID_KEY_PERFORMANCE_MODE,&mode,sizeof(mode));
    }
    if((*plyObj)->Realize(plyObj,SL_BOOLEAN_FALSE)!=SL_RESULT_SUCCESS) return -4;
    (*plyObj)->GetInterface(plyObj,SL_IID_PLAY,&playItf);
    (*plyObj)->GetInterface(plyObj,SL_IID_BUFFERQUEUE,&bq);
    (*bq)->RegisterCallback(bq,bqcb,NULL);
    for(int i=0;i<gNBuf;i++) (*bq)->Enqueue(bq,pcm[i],(SLuint32)(gBlock*2));
    (*playItf)->SetPlayState(playItf,SL_PLAYSTATE_PLAYING);
    LOGI("OpenSL up sr=%d block=%d nbuf=%d",gSr,gBlock,gNBuf);
    return 0;
}
JNIEXPORT jint JNICALL Java_com_hipster_tenoridroid_NativeAudio_nInit(JNIEnv *env,jclass c,jint sr,jint block,jint nbuf){
    if(block<64||block>MAXBLOCK||nbuf<2||nbuf>MAXBUF) return -10;
    slDestroy(); gSr=sr; gBlock=block; gNBuf=nbuf; gIdx=0; gRR=0;
    gFrames=0; gAnchor=nowNs(); gLastCb=0; gXrun=0; qh=qt=0; memset(V,0,sizeof(V));
    for(int i=0;i<1024;i++) sine[i]=sinf(2.f*3.14159265358979f*(float)i/1024.f);
    sine[1024]=sine[0];
    for(int i=0;i<MAXBUF;i++){ if(!pcm[i]) pcm[i]=(int16_t*)calloc(MAXBLOCK,sizeof(int16_t)); else memset(pcm[i],0,MAXBLOCK*sizeof(int16_t)); }
    if(!dly){ dmax=gSr*3/2; dly=(float*)calloc(dmax,sizeof(float)); } else memset(dly,0,dmax*sizeof(float));
    dlen=gSr/4; dpos=0;
    int r=slInit(); if(r!=0){ slDestroy(); return r; }
    return 0;
}
JNIEXPORT void JNICALL Java_com_hipster_tenoridroid_NativeAudio_nNoteOn(JNIEnv *env,jclass c,jint note,jint vel,jint snd,jint delay,jint gate,jfloat cut,jfloat atk,jfloat dec,jfloat sus,jfloat rel){
    Ev e; e.note=note;e.vel=vel;e.snd=snd;e.delay=delay;e.gate=gate;e.cut=cut;e.atk=atk;e.dec=dec;e.sus=sus;e.rel=rel;
    lock(); int nh=(qh+1)%MAXEV; if(nh!=qt){ Q[qh]=e; qh=nh; } unlock();
}
JNIEXPORT jint JNICALL Java_com_hipster_tenoridroid_NativeAudio_nDelay(JNIEnv *env,jclass c,jlong when){
    lock(); int64_t d=(when-gAnchor)*gSr/1000000000LL; unlock();
    if(d<0)d=0; if(d>gSr)d=gSr; return (jint)d;
}
JNIEXPORT void JNICALL Java_com_hipster_tenoridroid_NativeAudio_nPanic(JNIEnv *e,jclass c){ gPanic=1; }
JNIEXPORT void JNICALL Java_com_hipster_tenoridroid_NativeAudio_nMaster(JNIEnv *e,jclass c,jfloat v){ gMaster=v; }
JNIEXPORT jint JNICALL Java_com_hipster_tenoridroid_NativeAudio_nStats(JNIEnv *e,jclass c){ return gXrun; }
JNIEXPORT void JNICALL Java_com_hipster_tenoridroid_NativeAudio_nDelayCfg(JNIEnv *e,jclass c,jfloat wet,jfloat fb,jint len){
    gWet=wet; gFb=fb; if(len>16&&len<dmax){ dlen=len; if(dpos>=dlen)dpos=0; }
}
JNIEXPORT void JNICALL Java_com_hipster_tenoridroid_NativeAudio_nRelease(JNIEnv *e,jclass c){
    slDestroy();
    for(int i=0;i<MAXBUF;i++){ free(pcm[i]); pcm[i]=NULL; }
    free(dly); dly=NULL; dmax=0;
}
