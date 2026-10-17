package dev.pocketchat.tests;
import android.app.*;import android.os.*;import android.content.*;import android.net.*;import android.graphics.*;import android.view.*;import android.widget.*;import dev.pocketchat.*;import org.json.*;
public class AttachmentInstrumentation extends Instrumentation {
 public void onCreate(Bundle b){super.onCreate(b);start();}
 private int count;private void check(boolean b,String m){if(!b)throw new AssertionError(m);count++;}
 private int images(View v){int n=v instanceof ImageView?1:0;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)n+=images(g.getChildAt(i));}return n;}
 public void onStart(){Bundle result=new Bundle();try{
  final AttachmentStore store=new AttachmentStore(getTargetContext());
  JSONObject image=store.add(Uri.parse("content://dev.pocketchat.testfiles/image"));
  check(image.getString("mime").equals("image/jpeg"),"JPEG format");
  Bitmap thumb=store.thumbnail(image,160);check(thumb!=null&&thumb.getHeight()>thumb.getWidth(),"EXIF orientation and preview");check(thumb.getHeight()<=160,"Bounded thumbnail");thumb.recycle();
  JSONObject text=store.add(Uri.parse("content://dev.pocketchat.testfiles/text"));JSONObject pdf=store.add(Uri.parse("content://dev.pocketchat.testfiles/pdf"));check(pdf.getString("kind").equals("pdf"),"PDF import");
  boolean oversized=false;try{store.add(Uri.parse("content://dev.pocketchat.testfiles/large"));}catch(java.io.IOException expected){oversized=true;}check(oversized,"Oversized text rejected");
  JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","What is in this picture?").put("attachments",new JSONArray().put(image).put(text)));
  JSONArray hydrated=store.hydrate(history);check(hydrated.getJSONObject(0).getJSONArray("attachments").getJSONObject(0).getString("data").length()>0,"Image base64");check(hydrated.getJSONObject(0).getJSONArray("attachments").getJSONObject(1).getString("text").contains("café"),"UTF8 text");check(!history.toString().contains("data"),"Stored metadata has no base64");
  getTargetContext().getSharedPreferences("pocket",0).edit().putInt("provider",0).putString("model0","vision-model").putString("history0",history.toString()).putString("draftFiles0",new JSONArray().put(image).toString()).putString("effort0","low").commit();
  final Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  waitForIdleSync();final int[] previews={0};runOnMainSync(new Runnable(){public void run(){previews[0]=images(activity.getWindow().getDecorView());}});check(previews[0]>=2,"Log and composer image previews rendered");
  result.putString("stream","PASS: "+count+" Android attachment import, orientation, hydration and UI checks\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
