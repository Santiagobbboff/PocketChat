package dev.pocketchat;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
import org.json.*;

public final class StreamingTest {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static final class Sink implements Api.Listener {
        final StringBuilder text=new StringBuilder(); boolean thinking;
        public void onText(String part){text.append(part);}
        public void onThinking(){thinking=true;}
    }
    static final ReplyStream.Cancelled NO=new ReplyStream.Cancelled(){public boolean get(){return false;}};
    static String event(String json){return "data: "+json+"\r\n\r\n";}
    static String decode(int p,String wire,Sink sink)throws Exception {
        final byte[] bytes=wire.getBytes("UTF-8");
        InputStream fragmented=new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(1,len));}};
        return ReplyStream.read(p,fragmented,sink,NO);
    }
    static void fails(int p,String wire,String partial)throws Exception {
        Sink sink=new Sink();
        try{decode(p,wire,sink);throw new AssertionError("Expected stream failure");}
        catch(IOException expected){check(sink.text.toString().equals(partial),"partial survives failure");}
    }
    public static void main(String[] args)throws Exception {
        JSONArray history=new JSONArray("[{\"role\":\"user\",\"content\":\"Hi\"}]");
        for(int p=0;p<5;p++) {
            Api.Request r=Api.streamRequest(new Api.Config(p,Api.BASES[p],"key","models/test"),history);
            check(p==3?r.path.equals("/models/test:streamGenerateContent?alt=sse"):r.body.getBoolean("stream"),"stream request "+p);
        }
        Sink sink=new Sink();
        String ollama="{\"message\":{\"thinking\":\"private\"},\"done\":false}\n{\"message\":{\"content\":\"Hi 🌍\"},\"done\":false}\n{\"message\":{\"content\":\"!\"},\"done\":true}\n";
        check(decode(0,ollama,sink).equals("Hi 🌍!"),"fragmented UTF8 NDJSON");check(sink.thinking,"thinking status only");
        String openai=": keepalive\r\nevent: message\r\n"+event("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}")+
            "data: {\"choices\":\n"+"data: [{\"delta\":{\"content\":\"Hello\"}}]}\n\n"+
            event("{\"choices\":[{\"delta\":{\"content\":null,\"refusal\":\"!\"}}]}")+event("[DONE]");
        for(int p:new int[]{1,4})check(decode(p,openai,new Sink()).equals("Hello!"),"SSE multiline / null / DONE "+p);
        String claude=event("{\"type\":\"ping\"}")+event("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\",\"text\":\"Hi\"}}")+
            event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private\"}}")+
            event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" there\"}}")+event("{\"type\":\"message_stop\"}");
        check(decode(2,claude,new Sink()).equals("Hi there"),"Claude deltas");
        String gemini=event("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"private\"},{\"text\":\"Hi\"}]}}]}")+
            event("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"!\"}]},\"finishReason\":\"STOP\"}]}");
        check(decode(3,gemini,new Sink()).equals("Hi!"),"Gemini deltas");
        check(decode(1,event("{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}"),new Sink()).equals("done"),"terminal finish reason");
        check(decode(0,"{\"message\":{\"content\":\"done\"},\"done\":true}",new Sink()).equals("done"),"final line without newline");
        fails(0,"{\"message\":{\"content\":\"partial\"},\"done\":false}\n","partial");
        fails(1,event("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"),"partial");
        fails(2,event("{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}"),"");
        fails(0,"{\"error\":\"model unloaded\"}\n","");
        fails(0,"broken json\n","");
        fails(3,event("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}"),"");
        char[] large=new char[65000];Arrays.fill(large,'x');
        String limited=decode(0,new JSONObject().put("message",new JSONObject().put("content",new String(large))).put("done",true).toString()+"\n",new Sink());
        check(limited.length()==ReplyStream.MAX_TEXT+ReplyStream.LIMIT_NOTE.length(),"bounded output");
        final AtomicBoolean cancelled=new AtomicBoolean();
        try{
            ReplyStream.read(0,new ByteArrayInputStream(ollama.getBytes("UTF-8")),new Api.Listener(){public void onThinking(){}public void onText(String t){cancelled.set(true);}},new ReplyStream.Cancelled(){public boolean get(){return cancelled.get();}});
            throw new AssertionError("cancel was ignored");
        }catch(IOException expected){check(expected.getMessage().equals("Cancelled"),"cancel between chunks");}
        char[] huge=new char[256*1024+1];Arrays.fill(huge,'x');fails(0,new String(huge),"");
        // A live HTTP server refuses to finish until the client has delivered its first chunk.
        // This proves the network path does not buffer the complete answer.
        for(final int p:new int[]{0,1,2,3,4}) {
            final CountDownLatch firstSeen=new CountDownLatch(1);
            final AtomicBoolean early=new AtomicBoolean();
            final AtomicReference<Throwable> serverError=new AtomicReference<Throwable>();
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",ex->{
                try {
                    ByteArrayOutputStream request=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
                    while((n=ex.getRequestBody().read(b))!=-1)request.write(b,0,n);
                    JSONObject body=new JSONObject(request.toString("UTF-8"));
                    if(p!=3 && !body.getBoolean("stream"))throw new AssertionError("stream not requested");
                    if(p==3 && !ex.getRequestURI().toString().contains(":streamGenerateContent?alt=sse"))throw new AssertionError("Gemini endpoint");
                    ex.getResponseHeaders().set("Content-Type",p==0?"application/x-ndjson":"text/event-stream");
                    ex.sendResponseHeaders(200,0);
                    String first=p==0?"{\"message\":{\"content\":\"First\"},\"done\":false}\n":p==2?event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"First\"}}"):
                        p==3?event("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"First\"}]}}]}"):event("{\"choices\":[{\"delta\":{\"content\":\"First\"}}]}");
                    ex.getResponseBody().write(first.getBytes("UTF-8"));ex.getResponseBody().flush();
                    early.set(firstSeen.await(3,TimeUnit.SECONDS));
                    String last=p==0?"{\"message\":{\"content\":\" last\"},\"done\":true}\n":p==2?event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" last\"}}")+event("{\"type\":\"message_stop\"}"):
                        p==3?event("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" last\"}]},\"finishReason\":\"STOP\"}]}"):event("{\"choices\":[{\"delta\":{\"content\":\" last\"},\"finish_reason\":\"stop\"}]}");
                    ex.getResponseBody().write(last.getBytes("UTF-8"));
                }catch(Throwable t){serverError.set(t);}finally{ex.close();}
            });server.start();
            try {
                Api.Config c=new Api.Config(p,"http://127.0.0.1:"+server.getAddress().getPort(),"","test");
                Api.Request r=Api.streamRequest(c,history);
                Api.Listener listener=new Api.Listener(){public void onThinking(){}public void onText(String t){firstSeen.countDown();}};
                String result=p==0?new Api().streamChat(c,history,listener):new Api().exchange(c,r.path,r.body,listener);
                check(serverError.get()==null,"server no error "+p);check(early.get(),"first chunk before completion "+p);check(result.equals("First last"),"HTTP assembled response "+p);
            }finally{server.stop(0);}
        }
        System.out.println("PASS: "+checks+" streaming checks, including first-chunk delivery before completion for all five providers");
    }
}
