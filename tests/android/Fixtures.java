package dev.pocketchat.tests;
import android.content.*;import android.database.*;import android.net.*;import android.os.*;import android.graphics.*;import android.media.ExifInterface;import java.io.*;
public class Fixtures extends ContentProvider {
 public boolean onCreate(){return true;}
 public String getType(Uri uri){String p=uri.getLastPathSegment();return p.equals("image")?"image/jpeg":p.equals("pdf")?"application/pdf":"text/plain";}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){MatrixCursor c=new MatrixCursor(new String[]{"_display_name"});String p=uri.getLastPathSegment();c.addRow(new Object[]{p.equals("image")?"preview.jpg":p.equals("pdf")?"document.pdf":"notes.txt"});return c;}
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  try{String p=uri.getLastPathSegment();File f=new File(getContext().getFilesDir(),p);FileOutputStream out=new FileOutputStream(f);
   if(p.equals("image")){Bitmap b=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(0xff224e62);Paint paint=new Paint();paint.setColor(Color.WHITE);paint.setTextSize(64);canvas.drawText("Pocket Chat",70,180,paint);paint.setColor(0xff71dfca);canvas.drawCircle(650,270,120,paint);b.compress(Bitmap.CompressFormat.JPEG,90,out);b.recycle();out.close();ExifInterface exif=new ExifInterface(f.getPath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();}
   else{if(p.equals("large"))out.write(new byte[270000]);else out.write((p.equals("pdf")?"%PDF-1.4\nminimal test fixture":"Hello café\nfile contents").getBytes("UTF-8"));out.close();}
   return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
  }catch(Exception e){throw new FileNotFoundException(e.getMessage());}
 }
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
