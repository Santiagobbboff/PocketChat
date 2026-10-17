package dev.pocketchat;
import org.json.*;
import java.io.*;
import java.util.*;
/** Translates normalized messages without leaking local attachment metadata. */
public final class MessagePayload {
 public static JSONArray messages(int provider,JSONArray history)throws Exception{
  JSONArray result=new JSONArray();
  for(int i=0;i<history.length();i++){
   JSONObject m=history.getJSONObject(i);String role=m.getString("role"),text=m.optString("content","");JSONArray attachments=m.optJSONArray("attachments"),parts=new JSONArray(),images=new JSONArray();
   if(attachments!=null)for(int j=0;j<attachments.length();j++){JSONObject a=attachments.getJSONObject(j);if("text".equals(a.getString("kind")))text+="\n\n[Attached file: "+a.getString("name")+"]\n"+a.getString("text")+"\n[End attached file]";}
   if(!text.isEmpty())parts.put(provider==3?new JSONObject().put("text",text):new JSONObject().put("type","text").put("text",text));
   if(attachments!=null)for(int j=0;j<attachments.length();j++){
    JSONObject a=attachments.getJSONObject(j);String kind=a.getString("kind");if(kind.equals("text"))continue;
    String mime=a.getString("mime"),data=a.getString("data");boolean image=kind.equals("image");
    if(provider==0){if(!image)throw new IOException("Ollama cannot receive raw PDFs here. Attach a text file or images of the pages instead.");images.put(data);}
    else if(provider==3)parts.put(new JSONObject().put("inlineData",new JSONObject().put("mimeType",mime).put("data",data)));
    else if(provider==2)parts.put(new JSONObject().put("type",image?"image":"document").put("source",new JSONObject().put("type","base64").put("media_type",mime).put("data",data)));
    else if(image)parts.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:"+mime+";base64,"+data)));
    else parts.put(new JSONObject().put("type","file").put("file",new JSONObject().put("filename",a.getString("name")).put("file_data","data:"+mime+";base64,"+data)));
   }
   JSONObject out=new JSONObject().put("role",provider==3&&role.equals("assistant")?"model":role);
   if(provider==3)out.put("parts",parts);
   else if(provider==0){out.put("content",text);if(images.length()>0)out.put("images",images);}
   else out.put("content",attachments==null||attachments.length()==0?text:parts);
   result.put(out);
  }
  return result;
 }
 public static void effort(Api.Config c,JSONObject body)throws Exception{
  String effort=c.effort;if(effort.equals("default"))return;
  if(!Arrays.asList("low","medium","high","on","off").contains(effort))throw new IOException("Invalid thinking setting.");
  if(c.provider==0){body.put("think",effort.equals("on")?Boolean.TRUE:effort.equals("off")?Boolean.FALSE:effort);return;}
  if(effort.equals("on")||effort.equals("off"))throw new IOException("Use Default, Low, Medium or High for this provider.");
  int budget=effort.equals("low")?1024:effort.equals("medium")?4096:8192;
  if(c.provider==1||c.provider==4){body.put("reasoning_effort",effort);if(c.provider==1)body.put("max_completion_tokens",budget+4096);}
  else if(c.provider==2){
   String model=c.model.toLowerCase(Locale.US);
   boolean adaptive=model.matches(".*(?:opus|sonnet|haiku)-(?:4-[6-9]|[5-9]).*")||model.contains("mythos");
   if(adaptive)body.put("thinking",new JSONObject().put("type","adaptive")).put("output_config",new JSONObject().put("effort",effort)).put("max_tokens",budget+4096);
   else body.put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",budget)).put("max_tokens",budget+4096);
  }else if(c.provider==3){
   JSONObject thinking=new JSONObject();String model=c.model.toLowerCase(Locale.US);
   if(model.contains("gemini-2.5"))thinking.put("thinkingBudget",budget);
   else {if(effort.equals("medium")&&!model.contains("flash"))throw new IOException("For this Gemini model, choose Default, Low or High.");thinking.put("thinkingLevel",effort);}
   body.getJSONObject("generationConfig").put("thinkingConfig",thinking).put("maxOutputTokens",budget+4096);
  }
 }
}
