package dev.pocketchat;

import java.io.*;
import org.json.*;

/** Incremental NDJSON / SSE decoder. Works with arbitrarily fragmented UTF-8 input. */
final class ReplyStream {
    static final int MAX_TEXT=64000;
    static final String LIMIT_NOTE="\n[Reply stopped at this device's text limit]";
    interface Cancelled { boolean get(); }
    private final int provider;
    private final Api.Listener listener;
    private final StringBuilder text=new StringBuilder();
    private boolean done;
    private ReplyStream(int p,Api.Listener l){provider=p;listener=l;}

    static String read(int provider,InputStream input,Api.Listener listener,Cancelled cancelled) throws Exception {
        ReplyStream parser=new ReplyStream(provider,listener);
        BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"));
        StringBuilder event=new StringBuilder(); int total=0;
        try {
            while(!parser.done) {
                if(cancelled.get()) throw new IOException("Cancelled");
                // Bound individual lines and aggregate traffic without buffering the response.
                StringBuilder line=new StringBuilder(); int ch;
                while((ch=reader.read())!=-1 && ch!='\n') {
                    if(cancelled.get()) throw new IOException("Cancelled");
                    if(++total>8*1024*1024 || line.length()>=256*1024) throw new IOException("Stream is too large for this lightweight client.");
                    line.append((char)ch);
                }
                if(ch=='\n' && ++total>8*1024*1024) throw new IOException("Stream is too large for this lightweight client.");
                if(ch==-1 && line.length()==0) {
                    if(event.length()>0) parser.event(event.toString());
                    break;
                }
                if(line.length()>0 && line.charAt(line.length()-1)=='\r') line.setLength(line.length()-1);
                String value=line.toString();
                if(provider==0) {
                    if(!value.trim().isEmpty()) parser.event(value);
                } else if(value.isEmpty()) {
                    if(event.length()>0) { parser.event(event.toString()); event.setLength(0); }
                } else if(value.startsWith("data:")) {
                    String data=value.substring(5); if(data.startsWith(" ")) data=data.substring(1);
                    if(event.length()>0) event.append('\n');
                    if(event.length()+data.length()>256*1024) throw new IOException("Stream event is too large.");
                    event.append(data);
                }
                // SSE comments, id, retry and named event lines do not contain reply text.
            }
            if(cancelled.get()) throw new IOException("Cancelled");
            if(!parser.done) throw new IOException("Connection ended before the reply finished.");
            if(parser.text.toString().trim().isEmpty()) throw new IOException("No text returned. The model may have blocked the prompt or used its output budget before answering.");
            return parser.text.toString();
        } finally { reader.close(); }
    }
    private void append(String value) {
        if(done || value.isEmpty()) return;
        int remaining=MAX_TEXT-text.length();
        if(value.length()>remaining) {
            // Avoid splitting a UTF-16 surrogate pair at the local output limit.
            if(remaining>0 && Character.isHighSurrogate(value.charAt(remaining-1))) remaining--;
            String part=value.substring(0,remaining)+LIMIT_NOTE;
            text.append(part); listener.onText(part); done=true;
        } else { text.append(value); listener.onText(value); }
    }
    private void event(String data) throws Exception {
        if("[DONE]".equals(data.trim())) {done=true;return;}
        JSONObject r;
        try {r=new JSONObject(data);} catch(JSONException e) {throw new IOException("The server sent a malformed stream event.",e);}
        if(r.has("error") && !r.isNull("error")) {
            Object error=r.get("error");
            String message=error instanceof JSONObject?((JSONObject)error).optString("message","Provider error"):String.valueOf(error);
            throw new IOException("Provider error: "+message.substring(0,Math.min(400,message.length())));
        }
        if(provider==0) {
            JSONObject m=r.optJSONObject("message");
            if(m!=null) {
                if(!m.optString("thinking","").isEmpty()) listener.onThinking();
                append(m.optString("content",""));
            }
            if(r.optBoolean("done",false))done=true;
        } else if(provider==2) {
            String type=r.optString("type");
            JSONObject delta=r.optJSONObject("delta");
            if("content_block_delta".equals(type) && delta!=null) {
                if("text_delta".equals(delta.optString("type"))) append(delta.optString("text",""));
                else if("thinking_delta".equals(delta.optString("type")))listener.onThinking();
            } else if("content_block_start".equals(type)) {
                JSONObject block=r.optJSONObject("content_block");
                if(block!=null && "text".equals(block.optString("type")))append(block.optString("text",""));
            } else if("message_stop".equals(type)) done=true;
        } else if(provider==3) {
            JSONArray candidates=r.optJSONArray("candidates");
            if(candidates==null || candidates.length()==0) return;
            JSONObject candidate=candidates.getJSONObject(0), content=candidate.optJSONObject("content");
            JSONArray parts=content==null?null:content.optJSONArray("parts");
            if(parts!=null)for(int i=0;i<parts.length();i++) {
                JSONObject part=parts.getJSONObject(i);
                if(part.optBoolean("thought",false))listener.onThinking();
                else append(part.optString("text",""));
            }
            if(!candidate.optString("finishReason","").isEmpty())done=true;
        } else {
            JSONArray choices=r.optJSONArray("choices");
            if(choices==null || choices.length()==0)return;
            JSONObject choice=choices.getJSONObject(0),delta=choice.optJSONObject("delta");
            if(delta!=null) {
                if(!delta.isNull("content"))append(delta.optString("content",""));
                if(!delta.isNull("refusal"))append(delta.optString("refusal",""));
            }
            if(!choice.isNull("finish_reason") && !choice.optString("finish_reason","").isEmpty())done=true;
        }
    }
}
