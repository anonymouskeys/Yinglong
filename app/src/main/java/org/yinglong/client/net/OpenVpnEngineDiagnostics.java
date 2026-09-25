package org.yinglong.client.net;

import android.content.Context;
import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;
import java.io.File;
import java.util.Locale;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.NativeUtils;

final class OpenVpnEngineDiagnostics {
    private OpenVpnEngineDiagnostics() {}

    static void startup(Context c) {
        String dir = "?";
        boolean o3 = false, o2 = false, exec = false, bridge = false, selected = false;
        try {
            dir = c.getApplicationInfo().nativeLibraryDir;
            o3 = ok(dir, "libovpn3.so");
            o2 = ok(dir, "libopenvpn.so");
            exec = ok(dir, "libovpnexec.so");
        } catch (Throwable e) { AppLog.e("open-diag", "native inventory failed", e); }
        try { Class.forName("de.blinkt.openvpn.core.OpenVPNThreadv3", false, c.getClassLoader()); bridge = true; }
        catch (Throwable e) { AppLog.e("open-diag", "OpenVPN3 bridge missing", e); }
        try { selected = VpnProfile.doUseOpenVPN3(c); }
        catch (Throwable e) { AppLog.e("open-diag", "engine selection failed", e); }

        AppLog.i("open-diag", "ENGINE_MATRIX selected=" + (selected ? "OpenVPN3" : "OpenVPN2")
                + " bridge=" + bridge + " libovpn3=" + o3 + " libopenvpn2=" + o2
                + " libovpnexec=" + exec + " ovpn3Git=" + o3ver()
                + " ovpn2Git=" + o2ver() + " openssl=" + sslver());
        if (selected && o3 && bridge) AppLog.i("open-diag", "VERDICT engine-selection: OpenVPN3 ready");
        else AppLog.w("open-diag", "VERDICT engine-selection: OpenVPN3 incomplete/not selected");
    }

    static void profile(Relay r, VpnProfile p, String cfg, OpenVpnProfileUtil.Endpoint ep) {
        String tls = directive(cfg, "tls-version-min");
        String auth = directive(cfg, "auth");
        String cipher = val(p.mCipher), data = val(p.mDataCiphers);
        String scan=(tls+" "+auth+" "+cipher+" "+data).toLowerCase(Locale.US);
        boolean legacy="1.0".equals(tls)||scan.contains("bf-cbc")||scan.contains("sha1")||scan.contains("des");
        AppLog.i("open-diag", "PROFILE relay="+(r==null?"?":val(r.ip))
                +" proto="+(ep==null?"?":(ep.tcp?"tcp":"udp"))+" port="+(ep==null?-1:ep.port)
                +" tlsMin="+show(tls)+" cipher="+show(cipher)+" dataCiphers="+show(data)
                +" auth="+show(auth)+" inferredNeed="+(legacy?"OpenVPN3+legacyAlgorithms":"OpenVPN3-standard-first"));
    }

    static void signal(String line) {
        if (line==null) return;
        String x=line.toLowerCase(Locale.US);
        if (x.contains("unsupported protocol")||x.contains("protocol version"))
            AppLog.w("open-diag", "VERDICT legacy TLS required -> OpenVPN3 legacyAlgorithms");
        else if (x.contains("no valid translation found for tls cipher"))
            AppLog.w("open-diag", "VERDICT OpenVPN2 cipher translation failed -> OpenVPN3");
        else if (x.contains("verify ok: depth=0"))
            AppLog.i("open-diag", "SIGNAL TLS_CERT_OK relay answered/cert verified");
        else if (x.contains("peer connection initiated"))
            AppLog.i("open-diag", "VERDICT control-channel compatible");
        else if (x.contains("push_reply"))
            AppLog.i("open-diag", "VERDICT PUSH_REPLY received");
        else if (x.contains("initialization sequence completed")||x.contains("connected,succ"))
            AppLog.i("open-diag", "VERDICT SUCCESS current engine works");
        else if (x.contains("connect() error")||x.contains("event(error)")||x.contains("config file parse error"))
            AppLog.w("open-diag", "OPENVPN3_ERROR "+compact(line));
    }

    private static String directive(String cfg,String key){
        if(cfg==null)return "";
        for(String raw:cfg.split("\\r?\\n")){String line=raw.trim(); if(line.isEmpty()||line.startsWith("#")||line.startsWith(";"))continue;
            int sp=line.indexOf(' '); String name=sp<0?line:line.substring(0,sp); if(key.equalsIgnoreCase(name))return sp<0?"present":line.substring(sp+1).trim();}
        return "";
    }
    private static boolean ok(String d,String n){try{File f=new File(d,n);return f.isFile()&&f.length()>0;}catch(Throwable e){return false;}}
    private static String o2ver(){try{return compact(NativeUtils.getOpenVPN2GitVersion());}catch(Throwable e){return "ERR:"+e.getClass().getSimpleName();}}
    private static String o3ver(){try{return compact(NativeUtils.getOpenVPN3GitVersion());}catch(Throwable e){return "ERR:"+e.getClass().getSimpleName();}}
    private static String sslver(){try{return compact(NativeUtils.getOpenSSLVersion());}catch(Throwable e){return "ERR:"+e.getClass().getSimpleName();}}
    private static String compact(String s){if(s==null)return "?";String x=s.replace('\n',' ').replace('\r',' ').trim();return x.length()>180?x.substring(0,180)+"…":x;}
    private static String val(String s){return s==null?"":s.trim();}
    private static String show(String s){String x=val(s);return x.isEmpty()?"-":x;}
}
