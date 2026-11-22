package com.gtman.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 휴대폰 → 차 사진 직접 보내기.
 * 차가 휴대폰 핫스팟에 붙어 있을 때, 휴대폰 브라우저로 이 작은 서버에 사진을 올림.
 * 사진은 차 안에만 저장되고 밖으로 나가지 않음.
 */
public class PhotoServer {
    static final int PORT = 8080;
    private static ServerSocket server;
    private static Thread thread;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    static Runnable onChange;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "phone");
        d.mkdirs();
        return d;
    }

    static List<File> photos(Context c) {
        File[] fs = dir(c).listFiles();
        List<File> l = new ArrayList<>();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".jpg") && f.length() > 0) l.add(f);
        Collections.sort(l, (a, b) -> b.getName().compareTo(a.getName()));   // 최근 것 먼저
        return l;
    }

    static boolean running() { return server != null && !server.isClosed(); }

    static synchronized void start(Context ctx) {
        if (running()) return;
        final Context c = ctx.getApplicationContext();
        try {
            server = new ServerSocket(PORT);
        } catch (Exception e) {
            server = null;
            return;
        }
        thread = new Thread(() -> {
            while (running()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(c, s)).start();
                } catch (Exception ignored) { }
            }
        });
        thread.start();
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    /** 휴대폰 핫스팟에서 받은 차의 주소 */
    static String address() {
        try {
            String best = null;
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (n.getName().startsWith("wlan")) return "http://" + ip + ":" + PORT;
                    if (best == null) best = ip;
                }
            }
            return best == null ? null : "http://" + best + ":" + PORT;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static void handle(Context c, Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String method = parts[0], target = parts.length > 1 ? parts[1] : "/";
            int len = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0 && h.substring(0, i).trim().equalsIgnoreCase("content-length"))
                    len = Integer.parseInt(h.substring(i + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

            if (method.equals("POST") && path.equals("/up")) {
                byte[] body = readN(in, len);
                boolean ok = save(c, body);
                send(out, ok ? 200 : 400, "text/plain", (ok ? "ok" : "bad").getBytes());
                if (ok) changed();
            } else if (method.equals("POST") && path.equals("/del")) {
                readN(in, len);
                String n = param(query, "n");
                if (n != null && n.matches("[0-9A-Za-z_]+\\.jpg")) new File(dir(c), n).delete();
                send(out, 200, "text/plain", "ok".getBytes());
                changed();
            } else if (path.equals("/hud")) {
                send(out, 200, "text/html; charset=utf-8", readAsset(c, "hud.html"));
            } else if (path.equals("/hud.json")) {
                String j = PanelService.hudJson;
                send(out, 200, "application/json; charset=utf-8", (j == null ? "{}" : j).getBytes("UTF-8"));
            } else if (path.equals("/list")) {
                StringBuilder sb = new StringBuilder("[");
                for (File f : photos(c)) { if (sb.length() > 1) sb.append(','); sb.append('"').append(f.getName()).append('"'); }
                send(out, 200, "application/json", sb.append(']').toString().getBytes("UTF-8"));
            } else if (path.equals("/img")) {
                String n = param(query, "n");
                File f = n == null ? null : new File(dir(c), n);
                if (f == null || !n.matches("[0-9A-Za-z_]+\\.jpg") || !f.exists()) send(out, 404, "text/plain", "no".getBytes());
                else send(out, 200, "image/jpeg", readFile(f));
            } else {
                send(out, 200, "text/html; charset=utf-8", PAGE.getBytes("UTF-8"));
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] readAsset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void changed() {
        ui.post(() -> {
            if (PanelService.inst != null) PanelService.inst.reloadPhotos();
            if (onChange != null) onChange.run();
        });
    }

    /** 받은 사진은 차에 맞게 줄여서 저장 (긴 쪽 1600px) */
    private static boolean save(Context c, byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return false;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b == null) return false;
            int m = Math.max(b.getWidth(), b.getHeight());
            if (m > 1600) {
                float k = 1600f / m;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                if (s != b) b.recycle();
                b = s;
            }
            File f = new File(dir(c), "p" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 90, fo); }
            b.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
        }
        if (ch == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        if (n > 40 * 1024 * 1024) throw new Exception("too big");
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return off == n ? b : Arrays.copyOf(b, off);
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readN(in, (int) f.length()); }
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
    }

    // 휴대폰에서 열리는 화면
    private static final String PAGE = "<!doctype html><html lang=ko><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>싼슬라 사진</title><style>"
            + "body{margin:0;font-family:-apple-system,sans-serif;background:#0e1117;color:#fff;padding:20px 16px 40px}"
            + "h1{font-size:22px;margin:4px 0 18px;font-weight:600}"
            + "label{display:block;background:#3d6fe0;border-radius:16px;padding:18px;text-align:center;font-size:18px;font-weight:600}"
            + "input{display:none}#st{margin:14px 2px;color:#9aa3b5;font-size:15px;min-height:20px}"
            + "#g{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}"
            + ".c{position:relative;aspect-ratio:1;border-radius:10px;overflow:hidden;background:#1b2130}"
            + ".c img{width:100%;height:100%;object-fit:cover}"
            + ".c b{position:absolute;top:4px;right:4px;background:#000a;border-radius:50%;width:28px;height:28px;line-height:28px;text-align:center;font-size:15px}"
            + "</style></head><body><h1>🚗 싼슬라로 사진 보내기</h1>"
            + "<label>📷 사진 고르기<input id=f type=file accept='image/*' multiple></label><div id=st></div><div id=g></div>"
            + "<script>"
            + "const st=document.getElementById('st'),g=document.getElementById('g');"
            + "function shrink(file){return new Promise((ok,no)=>{const u=URL.createObjectURL(file),im=new Image();"
            + "im.onload=()=>{let w=im.naturalWidth,h=im.naturalHeight,k=Math.min(1,1600/Math.max(w,h));"
            + "const cv=document.createElement('canvas');cv.width=Math.round(w*k);cv.height=Math.round(h*k);"
            + "cv.getContext('2d').drawImage(im,0,0,cv.width,cv.height);URL.revokeObjectURL(u);cv.toBlob(b=>b?ok(b):no(),'image/jpeg',0.9)};"
            + "im.onerror=()=>{URL.revokeObjectURL(u);ok(file)};im.src=u})}"
            + "document.getElementById('f').onchange=async e=>{const fs=[...e.target.files];let n=0;"
            + "for(const f of fs){st.textContent='보내는 중… '+(n+1)+' / '+fs.length;"
            + "try{const b=await shrink(f);const r=await fetch('/up',{method:'POST',body:b});if(r.ok)n++}catch(x){}}"
            + "st.textContent=n+'장 보냈어요 ✓';e.target.value='';load()};"
            + "async function load(){const l=await (await fetch('/list')).json();g.innerHTML='';"
            + "l.forEach(n=>{const d=document.createElement('div');d.className='c';"
            + "d.innerHTML='<img loading=lazy src=\"/img?n='+n+'\"><b>✕</b>';"
            + "d.querySelector('b').onclick=async()=>{if(!confirm('이 사진을 차에서 지울까요?'))return;"
            + "await fetch('/del?n='+n,{method:'POST'});load()};g.appendChild(d)})}"
            + "load();</script></body></html>";
}
