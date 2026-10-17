package dev.pocketchat;
import org.json.*;import java.io.*;
public class AttachmentPayloadTest{
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static JSONArray history(String kind)throws Exception{JSONObject a=new JSONObject().put("id","private-id.bin").put("name",kind.equals("image")?"photo.jpg":kind.equals("pdf")?"report.pdf":"notes.txt").put("kind",kind).put("mime",kind.equals("image")?"image/jpeg":kind.equals("pdf")?"application/pdf":"text/plain");if(kind.equals("text"))a.put("text","Hello café\nline two");else a.put("data","YWJj");return new JSONArray().put(new JSONObject().put("role","user").put("content","Review").put("attachments",new JSONArray().put(a))).put(new JSONObject().put("role","assistant").put("content","OK"));}
 static Api.Config config(int p,String model,String effort){return new Api.Config(p,Api.BASES[p],"fake",model,effort);}
 public static void main(String[] args)throws Exception{
  for(int p=0;p<5;p++){
   JSONArray h=history("image");String original=h.toString();Api.Request r=Api.streamRequest(config(p,"model","default"),h);JSONObject b=r.body;
   check(h.toString().equals(original),"Input not mutated "+p);check(!b.toString().contains("private-id"),"Local ID not sent "+p);check(!b.has("thinking")&&!b.has("think")&&!b.has("reasoning_effort"),"Default effort omitted "+p);
   JSONArray m=b.getJSONArray(p==3?"contents":"messages");check(m.length()==2,"History retained "+p);
   if(p==0){check(m.getJSONObject(0).getJSONArray("images").getString(0).equals("YWJj"),"Ollama raw base64");check(m.getJSONObject(0).getString("content").equals("Review"),"Ollama text");}
   else if(p==3){check(m.getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("mimeType").equals("image/jpeg"),"Gemini inline image");check(m.getJSONObject(1).getString("role").equals("model"),"Gemini role");}
   else{JSONObject image=m.getJSONObject(0).getJSONArray("content").getJSONObject(1);if(p==2)check(image.getJSONObject("source").getString("data").equals("YWJj"),"Claude image source");else check(image.getJSONObject("image_url").getString("url").equals("data:image/jpeg;base64,YWJj"),"OpenAI image URL "+p);}
   JSONObject text=Api.chatRequest(config(p,"model","default"),history("text")).body;check(text.toString().contains("Hello café")&&text.toString().contains("notes.txt"),"Text attachment included "+p);
   if(p==0){try{Api.chatRequest(config(p,"model","default"),history("pdf"));throw new AssertionError("Ollama PDF accepted");}catch(IOException expected){checks++;}}
   else{JSONObject pdf=Api.chatRequest(config(p,"model","default"),history("pdf")).body;check(pdf.toString().contains("application/pdf"),"PDF mime "+p);if(p==2)check(pdf.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type").equals("document"),"Claude document");if(p==1||p==4)check(pdf.toString().contains("data:application/pdf;base64,YWJj"),"PDF data URL "+p);}
  }
  JSONArray h=new JSONArray().put(new JSONObject().put("role","user").put("content","Hi"));
  check(Api.chatRequest(config(0,"qwen3","on"),h).body.getBoolean("think"),"Ollama on");check(!Api.chatRequest(config(0,"qwen3","off"),h).body.getBoolean("think"),"Ollama off");
  for(String effort:new String[]{"low","medium","high"}){
   check(Api.chatRequest(config(0,"gpt-oss",effort),h).body.getString("think").equals(effort),"Ollama level");
   check(Api.chatRequest(config(1,"gpt-5",effort),h).body.getString("reasoning_effort").equals(effort),"OpenAI effort");
   check(Api.chatRequest(config(4,"custom",effort),h).body.getString("reasoning_effort").equals(effort),"Compatible effort");
   JSONObject legacy=Api.chatRequest(config(2,"claude-sonnet-4-5",effort),h).body;check(legacy.getJSONObject("thinking").getString("type").equals("enabled"),"Legacy Claude thinking");check(legacy.getJSONObject("thinking").getInt("budget_tokens")<legacy.getInt("max_tokens"),"Thinking budget leaves answer space");
   JSONObject adaptive=Api.chatRequest(config(2,"claude-opus-4-7",effort),h).body;check(adaptive.getJSONObject("thinking").getString("type").equals("adaptive")&&adaptive.getJSONObject("output_config").getString("effort").equals(effort),"Adaptive Claude");
   check(Api.chatRequest(config(3,"gemini-2.5-flash",effort),h).body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").has("thinkingBudget"),"Gemini 2.5 budget");
  }
  check(Api.chatRequest(config(3,"gemini-3-pro-preview","high"),h).body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel").equals("high"),"Gemini 3 level");
  check(Api.chatRequest(config(2,"claude-sonnet-4-6","low"),h).body.getJSONObject("thinking").getString("type").equals("adaptive"),"Claude 4.6 adaptive");
  System.out.println("PASS: "+checks+" attachment/effort payload checks");
 }
}
