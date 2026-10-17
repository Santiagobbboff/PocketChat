package dev.pocketchat;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

/** Small HTTP/JSON adapter. No SDKs, telemetry, redirects, or automatic retries. */
public final class Api {
    public static final String[] NAMES = {"Ollama (your PC)", "OpenAI", "Claude", "Gemini", "OpenAI-compatible"};
    public static final String[] BASES = {"http://192.168.1.100:11434", "https://api.openai.com/v1", "https://api.anthropic.com/v1", "https://generativelanguage.googleapis.com/v1beta", "https://example.com/v1"};
    public static final class Config {
        public final int provider;
        public final String base, key, model, effort;
        public Config(int p, String b, String k, String m) { this(p,b,k,m,"default"); }
        public Config(int p, String b, String k, String m, String e) {
            effort=e;
            provider=p; base=b.trim().replaceAll("/+$", ""); key=k.trim(); model=m.trim();
        }
        public void validate(boolean needsModel) throws Exception {
            URL u = new URL(base);
            if (!u.getProtocol().equals("https") && !(provider==0 && u.getProtocol().equals("http")))
                throw new IOException("Use HTTPS. Only local Ollama accepts HTTP.");
            if (u.getHost().isEmpty() || u.getUserInfo()!=null || u.getQuery()!=null || u.getRef()!=null)
                throw new IOException("Enter a base URL without credentials, query, or fragment.");
            if (needsModel && model.isEmpty()) throw new IOException("Choose a model in Settings first.");
            if (provider>0 && provider<4 && key.isEmpty()) throw new IOException("Enter your API key in Settings first.");
        }
    }
    public static final class Request {
        public final String path;
        public final JSONObject body;
        Request(String p, JSONObject b) { path=p; body=b; }
    }
    public static Request chatRequest(Config c, JSONArray history) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray converted=MessagePayload.messages(c.provider,history);
        if(c.provider==3){
            body.put("contents",converted).put("generationConfig",new JSONObject().put("maxOutputTokens",4096));
            MessagePayload.effort(c,body);
            String model=c.model.startsWith("models/")?c.model.substring(7):c.model;
            return new Request("/models/"+URLEncoder.encode(model,"UTF-8")+":generateContent",body);
        }
        body.put("model",c.model).put("messages",converted).put("stream",false);
        if(c.provider==2)body.put("max_tokens",4096);
        if(c.provider==1)body.put("max_completion_tokens",4096);
        MessagePayload.effort(c,body);
        return new Request(c.provider==0?"/api/chat":c.provider==2?"/messages":"/chat/completions",body);
    }
    public static String chatText(int p, JSONObject response) throws Exception {
        String result="";
        if (p==0) result=response.getJSONObject("message").optString("content", "");
        else if (p==2) {
            JSONArray parts=response.optJSONArray("content");
            if(parts!=null) for(int i=0;i<parts.length();i++) {
                JSONObject part=parts.getJSONObject(i);
                if("text".equals(part.optString("type"))) result+=part.optString("text");
            }
        } else if(p==3) {
            JSONArray candidates=response.optJSONArray("candidates");
            if(candidates!=null && candidates.length()>0) {
                JSONObject content=candidates.getJSONObject(0).optJSONObject("content");
                JSONArray parts=content==null?null:content.optJSONArray("parts");
                if(parts!=null) for(int i=0;i<parts.length();i++) {
                    JSONObject part=parts.getJSONObject(i);
                    if(!part.optBoolean("thought",false)) result+=part.optString("text", "");
                }
            }
        } else {
            JSONObject message=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            if(!message.isNull("content")) result=message.optString("content", "");
            if(result.isEmpty()) result=message.optString("refusal", "");
        }
        if(result.trim().isEmpty()) throw new IOException("No text returned. The model may have blocked the prompt, exhausted its output budget, or require tools. Try another text-chat model.");
        return result;
    }
    public interface RootSource { InputStream open() throws IOException; }
    private static volatile RootSource rootSource;
    private static SSLSocketFactory secureFactory;
    public static synchronized void setRootSource(RootSource source) { rootSource=source; secureFactory=null; }
    private static synchronized SSLSocketFactory secureFactory() throws Exception {
        if(secureFactory!=null) return secureFactory;
        SSLContext context=SSLContext.getInstance("TLS");
        TrustManager[] trust=null;
        if(rootSource!=null) {
            KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType()); roots.load(null,null);
            CertificateFactory certificates=CertificateFactory.getInstance("X.509");
            String pem=read(rootSource.open()); int count=0;
            for(String part:pem.split("-----END CERTIFICATE-----")) {
                int start=part.indexOf("-----BEGIN CERTIFICATE-----");
                if(start<0) continue;
                try {
                    byte[] der=(part.substring(start)+"-----END CERTIFICATE-----").getBytes("US-ASCII");
                    roots.setCertificateEntry("root"+count,certificates.generateCertificate(new ByteArrayInputStream(der))); count++;
                } catch(java.security.cert.CertificateException unsupported) { /* Fail closed for roots this OS cannot parse. */ }
            }
            if(count==0) throw new IOException("Could not load bundled CA certificates.");
            TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(roots); trust=managers.getTrustManagers();
        }
        context.init(null,trust,null); secureFactory=context.getSocketFactory(); return secureFactory;
    }
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;
    public void cancel() {
        cancelled=true;
        final HttpURLConnection c=connection;
        if(c!=null) new Thread(new Runnable(){public void run(){c.disconnect();}},"disconnect").start();
    }
    public interface Listener {
        void onText(String text);
        void onThinking();
    }
    public static Request streamRequest(Config config, JSONArray messages) throws Exception {
        Request request=chatRequest(config,messages);
        if(config.provider==3) return new Request(request.path.replace(":generateContent",":streamGenerateContent?alt=sse"),request.body);
        request.body.put("stream",true);
        return request;
    }
    public String streamChat(Config config, JSONArray messages, Listener listener) throws Exception {
        config.validate(true);
        Request request=streamRequest(config,messages);
        return exchange(config,request.path,request.body,listener);
    }
    public String chat(Config config, JSONArray messages) throws Exception {
        config.validate(true);
        Request request=chatRequest(config,messages);
        return chatText(config.provider, call(config,request.path,request.body));
    }
    public List<String> models(Config c) throws Exception {
        c.validate(false);
        TreeSet<String> names=new TreeSet<String>();
        String path=c.provider==0?"/api/tags":"/models";
        // Follow provider pagination, with a hard limit for an old low-memory phone.
        for(int page=0;page<20;page++) {
            JSONObject r=call(c,path,null);
            JSONArray a=r.optJSONArray(c.provider==0||c.provider==3?"models":"data");
            if(a==null) throw new IOException("Server did not return a model list. Enter the model ID manually.");
            for(int i=0;i<a.length();i++) {
                JSONObject m=a.getJSONObject(i);
                if(c.provider==3) {
                    JSONArray methods=m.optJSONArray("supportedGenerationMethods");
                    boolean chat=false;
                    if(methods!=null) for(int j=0;j<methods.length();j++) if("generateContent".equals(methods.getString(j))) chat=true;
                    if(!chat) continue;
                }
                String n=m.optString(c.provider==0||c.provider==3?"name":"id");
                if(n.startsWith("models/")) n=n.substring(7);
                if(!n.isEmpty()) names.add(n);
            }
            if(c.provider==3 && !r.optString("nextPageToken").isEmpty())
                path="/models?pageToken="+URLEncoder.encode(r.getString("nextPageToken"),"UTF-8");
            else if(c.provider==2 && r.optBoolean("has_more") && !r.optString("last_id").isEmpty())
                path="/models?after_id="+URLEncoder.encode(r.getString("last_id"),"UTF-8");
            else break;
        }
        return new ArrayList<String>(names);
    }
    JSONObject call(Config c,String path,JSONObject body) throws Exception {
        return new JSONObject(exchange(c,path,body,null));
    }
    String exchange(Config c,String path,JSONObject body,Listener listener) throws Exception {
        if(cancelled) throw new IOException("Cancelled");
        HttpURLConnection h=(HttpURLConnection)new URL(c.base+path).openConnection();
        connection=h;
        try {
            if(cancelled) throw new IOException("Cancelled");
            if(h instanceof HttpsURLConnection) ((HttpsURLConnection)h).setSSLSocketFactory(new Tls12());
            h.setConnectTimeout(20000); h.setReadTimeout(180000); h.setInstanceFollowRedirects(false);
            h.setRequestProperty("Accept",listener==null?"application/json":c.provider==0?"application/x-ndjson":"text/event-stream");
            if(!c.key.isEmpty()) {
                if(c.provider==2) h.setRequestProperty("x-api-key",c.key);
                else if(c.provider==3) h.setRequestProperty("x-goog-api-key",c.key);
                else h.setRequestProperty("Authorization","Bearer "+c.key);
            }
            if(c.provider==2) h.setRequestProperty("anthropic-version","2023-06-01");
            if(body!=null) {
                byte[] bytes=body.toString().getBytes("UTF-8");
                h.setRequestMethod("POST"); h.setDoOutput(true);
                h.setRequestProperty("Content-Type","application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(bytes.length);
                OutputStream out=h.getOutputStream(); try{out.write(bytes);}finally{out.close();}
            }
            int status=h.getResponseCode();
            InputStream in=status>=200&&status<300?h.getInputStream():h.getErrorStream();
            if(status<200||status>=300) {
                String text=read(in);
                if(cancelled) throw new IOException("Cancelled");
                String detail="";
                try {
                    JSONObject r=new JSONObject(text); Object error=r.opt("error");
                    detail=error instanceof JSONObject?((JSONObject)error).optString("message"):error instanceof String?(String)error:"";
                }catch(Exception ignored){}
                if(!c.key.isEmpty()) detail=detail.replace(c.key,"[redacted]");
                if(detail.length()>400) detail=detail.substring(0,400);
                throw new IOException("HTTP "+status+". "+(status==401||status==403?"Check your API key and access. ":status==429?"Rate limit or quota reached. Wait or check billing. ":status>=300&&status<400?"Redirect refused; update the base URL. ":"")+detail);
            }
            if(listener==null) return read(in);
            String type=h.getContentType();
            if(c.provider!=0 && type!=null && type.toLowerCase(Locale.US).contains("application/json")) {
                // A compatible server may ignore stream=true. Do not retry or bill twice.
                String reply=chatText(c.provider,new JSONObject(read(in)));
                if(cancelled) throw new IOException("Cancelled");
                if(reply.length()>ReplyStream.MAX_TEXT) reply=reply.substring(0,ReplyStream.MAX_TEXT)+ReplyStream.LIMIT_NOTE;
                listener.onText(reply); return reply;
            }
            try {
                return ReplyStream.read(c.provider,in,listener,new ReplyStream.Cancelled(){public boolean get(){return cancelled;}});
            } catch(IOException error) {
                String message=error.getMessage();
                if(message!=null && !c.key.isEmpty()) message=message.replace(c.key,"[redacted]");
                throw new IOException(message,error);
            }
        } finally { h.disconnect(); connection=null; }
    }
    private static String read(InputStream in) throws IOException {
        if(in==null) return "";
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
            while((n=in.read(b))!=-1) {
                if(out.size()+n>2*1024*1024) throw new IOException("Response is too large for this lightweight client.");
                out.write(b,0,n);
            }
            return out.toString("UTF-8");
        }finally{in.close();}
    }
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12() throws Exception { delegate=secureFactory(); }
        private Socket enable(Socket s) {
            if(s instanceof SSLSocket) {
                SSLSocket ssl=(SSLSocket)s; List<String> protocols=new ArrayList<String>();
                for(String p:ssl.getSupportedProtocols()) if(p.equals("TLSv1.2")||p.equals("TLSv1.3")) protocols.add(p);
                ssl.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
            }
            return s;
        }
        public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
        public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
        public Socket createSocket() throws IOException{return enable(delegate.createSocket());}
        public Socket createSocket(Socket s,String h,int p,boolean close)throws IOException{return enable(delegate.createSocket(s,h,p,close));}
        public Socket createSocket(String h,int p)throws IOException{return enable(delegate.createSocket(h,p));}
        public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return enable(delegate.createSocket(h,p,l,lp));}
        public Socket createSocket(InetAddress h,int p)throws IOException{return enable(delegate.createSocket(h,p));}
        public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return enable(delegate.createSocket(h,p,l,lp));}
    }
}
