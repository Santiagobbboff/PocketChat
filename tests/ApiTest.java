package dev.pocketchat;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import com.sun.net.httpserver.*;

/** Run on a desktop JDK with org.json on the classpath. No real credentials or paid calls. */
public final class ApiTest {
    static int checks;
    static void check(boolean b,String name){checks++;if(!b)throw new AssertionError(name);}
    static void fails(RunnableEx r,String message)throws Exception{try{r.run();throw new AssertionError(message);}catch(IOException expected){checks++;}}
    interface RunnableEx {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        JSONArray h=new JSONArray("[{\"role\":\"user\",\"content\":\"Hi ☀\"},{\"role\":\"assistant\",\"content\":\"Hello\"},{\"role\":\"user\",\"content\":\"Again\"}]");
        for(int p=0;p<5;p++) {
            Api.Config c=new Api.Config(p,Api.BASES[p],"test-key","example-model");
            c.validate(true);Api.Request r=Api.chatRequest(c,h);
            if(p==3){check(r.path.equals("/models/example-model:generateContent"),"Gemini path");check(r.body.getJSONArray("contents").getJSONObject(1).getString("role").equals("model"),"Gemini assistant conversion");}
            else {check(r.body.getJSONArray("messages").length()==3,"history preserved");check(!r.body.getBoolean("stream"),"nonstream");}
            if(p==2)check(r.body.getInt("max_tokens")==4096,"Claude token limit");
            if(p==1)check(r.body.getInt("max_completion_tokens")==4096,"OpenAI token limit");
        }
        check(Api.chatText(0,new JSONObject("{\"message\":{\"content\":\"ollama\"}}" )).equals("ollama"),"Ollama reply");
        check(Api.chatText(1,new JSONObject("{\"choices\":[{\"message\":{\"content\":\"openai\"}}]}" )).equals("openai"),"OpenAI reply");
        check(Api.chatText(2,new JSONObject("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"secret\"},{\"type\":\"text\",\"text\":\"claude\"}]}" )).equals("claude"),"Claude text only");
        check(Api.chatText(3,new JSONObject("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"secret\"},{\"text\":\"gemini\"}]}}]}" )).equals("gemini"),"Gemini text only");
        fails(()->Api.chatText(3,new JSONObject("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")),"blocked Gemini");
        fails(()->new Api.Config(1,"http://example.com/v1","secret","m").validate(true),"cloud HTTP forbidden");
        fails(()->new Api.Config(0,"http://user:pass@localhost:11434","","m").validate(true),"URL credentials forbidden");
        fails(()->new Api.Config(0,"http://localhost:11434","","").validate(true),"empty model rejected");
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final List<String> seen=new ArrayList<String>();
        server.createContext("/",ex->{
            String path=ex.getRequestURI().getPath();
            seen.add(path);
            String out="{}";int code=200;
            if(path.equals("/api/chat")){
                check(ex.getRequestMethod().equals("POST"),"POST verb");
                check("Bearer test-key".equals(ex.getRequestHeaders().getFirst("Authorization")),"bearer auth");
                ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] data=new byte[512];int n;while((n=ex.getRequestBody().read(data))!=-1)b.write(data,0,n);
                check(new JSONObject(b.toString("UTF-8")).getJSONArray("messages").getJSONObject(0).getString("content").equals("Hi ☀"),"UTF8 round trip");
                out="{\"message\":{\"content\":\"mock reply\"}}";
            } else if(path.equals("/api/tags"))out="{\"models\":[{\"name\":\"z\"},{\"name\":\"a\"}]}";
            else if(path.equals("/redirect")){code=302;ex.getResponseHeaders().add("Location","/leak");}
            else if(path.equals("/quota")){code=429;out="{\"error\":{\"message\":\"quota test-key\"}}";}
            else if(path.equals("/claude")){
                check("test-key".equals(ex.getRequestHeaders().getFirst("x-api-key")),"Claude auth");
                check("2023-06-01".equals(ex.getRequestHeaders().getFirst("anthropic-version")),"Claude version");
            }else if(path.equals("/gemini"))check("test-key".equals(ex.getRequestHeaders().getFirst("x-goog-api-key")),"Gemini header auth");
            byte[] bytes=out.getBytes("UTF-8");ex.sendResponseHeaders(code,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });
        server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();Api api=new Api();Api.Config c=new Api.Config(0,base,"test-key","test");
            check(api.chat(c,h).equals("mock reply"),"HTTP chat end to end");
            check(api.models(c).equals(Arrays.asList("a","z")),"model discovery");
            fails(()->api.call(c,"/redirect",null),"redirect refused");check(!seen.contains("/leak"),"no credential redirect");
            try{api.call(c,"/quota",null);throw new AssertionError("quota");}catch(IOException e){check(e.getMessage().contains("429")&&!e.getMessage().contains("test-key"),"redacted quota error");}
            api.call(new Api.Config(2,base,"test-key","m"),"/claude",new JSONObject());
            api.call(new Api.Config(3,base,"test-key","m"),"/gemini",new JSONObject());
            Api cancelled=new Api();cancelled.cancel();fails(()->cancelled.chat(c,h),"cancelled request");
        }finally{server.stop(0);}
        System.out.println("PASS: "+checks+" adapter/HTTP checks");
    }
}
