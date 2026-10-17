package dev.pocketchat;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.OpenableColumns;import android.graphics.*;import android.media.ExifInterface;import android.util.Base64;
import org.json.*;import java.io.*;import java.nio.*;import java.nio.charset.*;import java.util.*;
/** Imports bounded private copies, never stores binary blobs in preferences. */
public final class AttachmentStore {
 private final Context context;private final File directory;
 public AttachmentStore(Context c){context=c;directory=new File(c.getFilesDir(),"attachments");directory.mkdirs();}
 public File file(JSONObject a)throws Exception{String id=a.getString("id");if(!id.matches("[a-f0-9-]+\\.bin"))throw new IOException("Invalid attachment reference.");return new File(directory,id);}
 public JSONObject add(Uri uri)throws Exception{
  String name="attachment",mime=context.getContentResolver().getType(uri);Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
  if(cursor!=null)try{if(cursor.moveToFirst())name=cursor.getString(0);}finally{cursor.close();}if(name==null)name="attachment";if(name.length()>180)name=name.substring(0,180);
  String lower=name.toLowerCase(Locale.US);boolean image=mime!=null&&mime.startsWith("image/")||lower.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$");
  boolean pdf="application/pdf".equals(mime)||lower.endsWith(".pdf");boolean text=mime!=null&&mime.startsWith("text/")||lower.matches(".*\\.(txt|md|csv|tsv|json|xml|html|css|js|ts|py|java|c|cpp|h|sh|log|yaml|yml|ini|sql|rs|go)$");
  if(!image&&!pdf&&!text)throw new IOException("Supported: images, PDF, and UTF-8 text/code files. Convert Office files to PDF or text first.");
  File temp=File.createTempFile("import-",".tmp",directory),dest=new File(directory,UUID.randomUUID().toString()+".bin");boolean done=false;
  try{
   InputStream in=context.getContentResolver().openInputStream(uri);if(in==null)throw new IOException("Cannot open file.");try{FileOutputStream out=new FileOutputStream(temp);try{byte[] buf=new byte[8192];int n,total=0,limit=image?8*1024*1024:pdf?2*1024*1024:256*1024;while((n=in.read(buf))!=-1){total+=n;if(total>limit)throw new IOException(image?"Image exceeds 8 MB input limit.":pdf?"PDF exceeds 2 MB limit.":"Text file exceeds 256 KB limit.");out.write(buf,0,n);}}finally{out.close();}}finally{in.close();}
   if(image){
    BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(temp.getPath(),opts);if(opts.outWidth<=0||opts.outHeight<=0)throw new IOException("This image format cannot be decoded on Android 4.4. Use JPEG or PNG.");
    opts.inSampleSize=1;while(opts.outWidth/opts.inSampleSize>1600||opts.outHeight/opts.inSampleSize>1600)opts.inSampleSize*=2;opts.inJustDecodeBounds=false;Bitmap original=BitmapFactory.decodeFile(temp.getPath(),opts);if(original==null)throw new IOException("Could not decode image.");
    Bitmap oriented=original;
    try{int o=new ExifInterface(temp.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();switch(o){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;}if(o>=2&&o<=8)oriented=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),m,true);}catch(IOException ignored){}
    float ratio=Math.min(1f,1280f/Math.max(oriented.getWidth(),oriented.getHeight()));Bitmap output=Bitmap.createBitmap(Math.max(1,Math.round(oriented.getWidth()*ratio)),Math.max(1,Math.round(oriented.getHeight()*ratio)),Bitmap.Config.ARGB_8888);
    try{Canvas canvas=new Canvas(output);canvas.drawColor(Color.WHITE);canvas.drawBitmap(oriented,null,new Rect(0,0,output.getWidth(),output.getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));FileOutputStream out=new FileOutputStream(dest);try{if(!output.compress(Bitmap.CompressFormat.JPEG,85,out))throw new IOException("Could not encode image.");}finally{out.close();}}finally{output.recycle();if(oriented!=original)oriented.recycle();original.recycle();}
    mime="image/jpeg";
   }else{
    byte[] bytes=read(temp,2*1024*1024);
    if(pdf){if(bytes.length<5||!new String(bytes,0,5,"US-ASCII").equals("%PDF-"))throw new IOException("This file is not a PDF.");mime="application/pdf";}
    else{utf8(bytes);mime="text/plain";}
    if(!temp.renameTo(dest))throw new IOException("Cannot save attachment.");
   }
   if(dest.length()>2*1024*1024)throw new IOException("Processed attachment exceeds 2 MB.");
   JSONObject a=new JSONObject().put("id",dest.getName()).put("name",name).put("mime",mime).put("kind",image?"image":pdf?"pdf":"text").put("size",dest.length());done=true;return a;
  }finally{temp.delete();if(!done)dest.delete();}
 }
 public Bitmap thumbnail(JSONObject a,int pixels){try{File f=file(a);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),o);o.inSampleSize=1;while(o.outWidth/o.inSampleSize>pixels||o.outHeight/o.inSampleSize>pixels)o.inSampleSize*=2;o.inJustDecodeBounds=false;return BitmapFactory.decodeFile(f.getPath(),o);}catch(Exception e){return null;}}
 public JSONArray hydrate(JSONArray history)throws Exception{
  JSONArray copy=new JSONArray(history.toString());long total=0;
  for(int i=0;i<copy.length();i++){JSONArray files=copy.getJSONObject(i).optJSONArray("attachments");if(files==null)continue;for(int j=0;j<files.length();j++){JSONObject a=files.getJSONObject(j);File f=file(a);total+=f.length();if(total>4*1024*1024)throw new IOException("Attachment context exceeds 4 MB. Start a new chat to send more files.");byte[] bytes=read(f,2*1024*1024);if(a.getString("kind").equals("text"))a.put("text",utf8(bytes));else a.put("data",Base64.encodeToString(bytes,Base64.NO_WRAP));}}
  return copy;
 }
 private static String utf8(byte[] b)throws IOException{try{String s=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();if(s.indexOf('\0')>=0)throw new IOException("Binary file is not UTF-8 text.");return s;}catch(CharacterCodingException e){throw new IOException("Save text files as UTF-8 before attaching.");}}
 private static byte[] read(File f,int limit)throws IOException{InputStream in=new FileInputStream(f);try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Attachment too large.");out.write(buf,0,n);}return out.toByteArray();}finally{in.close();}}
 public void prune(Set<String> keep){File[] all=directory.listFiles();if(all!=null)for(File f:all)if(!keep.contains(f.getName()))f.delete();}
}
