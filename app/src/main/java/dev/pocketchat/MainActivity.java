package dev.pocketchat;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int BG=0xff111a20, PANEL=0xff1b2932, INK=0xffe8f0f3, MUTED=0xff9aadb8, ACCENT=0xff71dfca;
    private android.content.SharedPreferences prefs;
    private final String[] keys=new String[Api.NAMES.length];
    private final String[] drafts=new String[Api.NAMES.length];
    private int provider=0, generation=0;
    private JSONArray history=new JSONArray();
    private LinearLayout messages;
    private ScrollView scroll;
    private Spinner selector;
    private EditText input;
    private TextView status, subtitle;
    private Button send, settings, clear, attach, thinking;
    private LinearLayout attachmentTray;
    private AttachmentStore attachmentStore;
    private JSONArray attached=new JSONArray(),pendingFiles=new JSONArray();
    private boolean importing;

    private Api active;
    private String pending="";
    private LiveReply live;
    private TextView liveText;
    private long requestStarted, lastStatus;
    private boolean receivedText;
    private static final class LiveReply implements Api.Listener {
        private final StringBuilder text=new StringBuilder();
        private int delivered;
        private boolean thinking, closed;
        public synchronized void onText(String chunk){if(!closed)text.append(chunk);}
        public synchronized void onThinking(){if(!closed)thinking=true;}
        synchronized String takeNew(){String part=text.substring(delivered);delivered=text.length();return part;}
        synchronized boolean isThinking(){return thinking;}
        synchronized String close(){closed=true;return text.toString();}
    }
    private boolean atBottom(){return messages.getHeight()-scroll.getHeight()-scroll.getScrollY()<=dp(64);}
    private final Runnable liveTick=new Runnable(){public void run(){
        if(destroyed||active==null||live==null)return;
        String part=live.takeNew();
        if(!part.isEmpty()) {
            final boolean follow=atBottom();
            if(!receivedText){liveText.setText("");receivedText=true;}
            liveText.append(part);
            if(follow)scroll.post(new Runnable(){public void run(){scroll.fullScroll(View.FOCUS_DOWN);}});
        }
        long now=SystemClock.elapsedRealtime();
        if(now-lastStatus>=400) {
            lastStatus=now;int dots=(int)((now-requestStarted)/400)%4;
            String suffix="";for(int i=0;i<dots;i++)suffix+=".";
            String phase=receivedText?"Receiving":live.isThinking()?"Model is thinking":"Waiting for first text";
            status.setText(phase+suffix+" • "+((now-requestStarted)/1000)+"s");
            if(!receivedText)liveText.setText((live.isThinking()?"Thinking":"Waiting for model")+suffix);
        }
        main.postDelayed(this,50);
    }};
    private boolean destroyed=false;
    private final Handler main=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final android.content.res.Resources resources=getApplicationContext().getResources();
        Api.setRootSource(new Api.RootSource(){public java.io.InputStream open(){return resources.openRawResource(R.raw.ca_bundle);}});
        prefs=getSharedPreferences("pocket",MODE_PRIVATE);
        attachmentStore=new AttachmentStore(getApplicationContext());
        for(int i=0;i<keys.length;i++){keys[i]=prefs.getString("key"+i,"");drafts[i]="";}
        provider=prefs.getInt("provider",0);
        if(provider<0||provider>=Api.NAMES.length)provider=0;
        if(state!=null) for(int i=0;i<drafts.length;i++) drafts[i]=state.getString("draft"+i,"");
        build(); load(); pruneAttachments();
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable surface(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setTextColor(INK);return b;}
    private void build(){
        LinearLayout root=column();root.setPadding(dp(12),dp(12),dp(12),dp(8));root.setBackgroundColor(BG);
        TextView title=label("Pocket Chat",26,INK);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        subtitle=label("Small app. Your choice of model.",12,MUTED);root.addView(subtitle);
        selector=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,Api.NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);selector.setAdapter(adapter);selector.setSelection(provider);
        root.addView(selector,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout actions=new LinearLayout(this);settings=button("Settings");clear=button("New chat");
        actions.addView(settings,new LinearLayout.LayoutParams(0,dp(44),1));actions.addView(clear,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(actions);
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=column();scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=label("",12,MUTED);status.setPadding(dp(4),dp(6),dp(4),dp(6));root.addView(status);
        LinearLayout extras=new LinearLayout(this);attach=button("Attach file");thinking=button("Thinking: Default");extras.addView(attach,new LinearLayout.LayoutParams(0,dp(42),1));extras.addView(thinking,new LinearLayout.LayoutParams(0,dp(42),2));root.addView(extras);
        HorizontalScrollView trayScroll=new HorizontalScrollView(this);attachmentTray=new LinearLayout(this);trayScroll.addView(attachmentTray);root.addView(trayScroll);
        attach.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(attached.length()>=4){status.setText("Up to four attachments per message.");return;}Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT);choose.setType("*/*");choose.addCategory(Intent.CATEGORY_OPENABLE);choose.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivityForResult(choose,201);}catch(ActivityNotFoundException e){status.setText("No document picker is installed.");}}});
        thinking.setOnClickListener(new View.OnClickListener(){public void onClick(View v){chooseThinking();}});
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM);
        input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setHint("Message…");input.setTextSize(16);input.setMinLines(1);input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));send=button("Send");send.setTextColor(ACCENT);composer.addView(send,new LinearLayout.LayoutParams(dp(76),dp(52)));root.addView(composer);
        setContentView(root);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){
                if(pos!=provider){drafts[provider]=input.getText().toString();provider=pos;prefs.edit().putInt("provider",pos).apply();load();}
            }
        });
        settings.setOnClickListener(new View.OnClickListener(){public void onClick(View v){showSettings();}});
        clear.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            new AlertDialog.Builder(MainActivity.this).setTitle("Start a new chat?").setMessage("This deletes the saved chat for this provider.")
                .setNegativeButton("Cancel",null).setPositiveButton("New chat",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){history=new JSONArray();attached=new JSONArray();saveDraftFiles();saveHistory();pruneAttachments();render();status.setText("New chat ready");}}).show();
        }});
        send.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(active!=null)stop();else sendMessage();}});
    }
    private Api.Config config(){return new Api.Config(provider,prefs.getString("base"+provider,Api.BASES[provider]),keys[provider],prefs.getString("model"+provider,""),prefs.getString("effort"+provider,"default"));}
    private void load(){
        try{history=new JSONArray(prefs.getString("history"+provider,"[]"));}catch(Exception e){history=new JSONArray();}
        try{attached=new JSONArray(prefs.getString("draftFiles"+provider,"[]"));}catch(Exception e){attached=new JSONArray();}
        input.setText(drafts[provider]);pending="";pendingFiles=new JSONArray();render();status.setText(config().model.isEmpty()?"Open Settings to connect a model.":"Ready • keeps the latest 20 exchanges");
    }
    private void saveHistory(){prefs.edit().putString("history"+provider,history.toString()).apply();}
    private TextView bubble(String role,String text){
        LinearLayout box=column();box.setPadding(dp(12),dp(10),dp(12),dp(10));box.setBackground(surface(role.equals("YOU")?0xff203c3e:PANEL));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(role.equals("YOU")?dp(24):0,dp(8),role.equals("YOU")?0:dp(16),0);messages.addView(box,lp);
        TextView who=label(role,10,ACCENT);who.setTypeface(null,Typeface.BOLD);box.addView(who);
        TextView body=label(text,16,INK);body.setPadding(0,dp(5),0,0);body.setTextIsSelectable(true);box.addView(body);return body;
    }
    private void render(){
        messages.removeAllViews();String model=config().model;subtitle.setText(model.isEmpty()?"Small app. Your choice of model.":model);
        if(history.length()==0&&pending.isEmpty()){
            TextView empty=label("Hello, small screen.\n\nChoose a provider, open Settings, and connect a model.\n\nYour chats stay separate for each provider.",18,MUTED);empty.setPadding(dp(16),dp(36),dp(16),dp(24));messages.addView(empty);
        }
        for(int i=0;i<history.length();i++){JSONObject m=history.optJSONObject(i);if(m!=null){TextView body=bubble("user".equals(m.optString("role"))?"YOU":"ASSISTANT",m.optString("content"));addPreviews((LinearLayout)body.getParent(),m.optJSONArray("attachments"),false);}}
        if(!pending.isEmpty()){TextView body=bubble("YOU",pending);addPreviews((LinearLayout)body.getParent(),pendingFiles,false);}
        renderAttachments();thinking.setText("Thinking: "+prefs.getString("effort"+provider,"default"));
        scroll.post(new Runnable(){public void run(){scroll.fullScroll(View.FOCUS_DOWN);}});
    }
    private void busy(boolean b){attach.setEnabled(!b);thinking.setEnabled(!b);send.setEnabled(!importing);selector.setEnabled(!b);settings.setEnabled(!b);clear.setEnabled(!b);input.setEnabled(!b);send.setText(b?"Stop":"Send");}
    private boolean commitReply(String prompt,String reply){
        try {
            JSONArray all=new JSONArray(history.toString());
            all.put(new JSONObject().put("role","user").put("content",prompt).put("attachments",new JSONArray(pendingFiles.toString())));
            all.put(new JSONObject().put("role","assistant").put("content",reply));
            int first=Math.max(0,all.length()-40),chars=0;
            for(int i=all.length()-1;i>=first;i--) {
                chars+=all.getJSONObject(i).optString("content").length();
                if(chars>120000){first=(i/2+1)*2;break;}
            }
            JSONArray keep=new JSONArray();for(int i=first;i<all.length();i++)keep.put(all.get(i));
            history=keep;attached=new JSONArray();saveDraftFiles();saveHistory();pruneAttachments();return true;
        }catch(Exception error){return false;}
    }
    private String finishLive(){
        main.removeCallbacks(liveTick);
        String partial=live==null?"":live.close();live=null;active=null;return partial;
    }
    private void sendMessage(){
        final String text=input.getText().toString().trim().isEmpty()&&attached.length()>0?"Please review the attached files.":input.getText().toString().trim();if(text.isEmpty())return;
        if(text.length()>32000){status.setText("Please keep each message under 32,000 characters.");return;}
        final Api.Config c=config();
        try{c.validate(true);}catch(Exception e){status.setText(e.getMessage());return;}
        final JSONArray request;
        try{request=new JSONArray(history.toString());request.put(new JSONObject().put("role","user").put("content",text).put("attachments",new JSONArray(attached.toString())));}catch(Exception e){status.setText("Could not prepare message.");return;}
        final Api api=new Api();active=api;final int token=++generation;
        final LiveReply buffer=new LiveReply();live=buffer;receivedText=false;
        pendingFiles=attached;pending=text;input.setText("");busy(true);render();liveText=bubble("ASSISTANT","Waiting for model…");
        requestStarted=SystemClock.elapsedRealtime();lastStatus=0;main.post(liveTick);
        new Thread(new Runnable(){public void run(){
            try{
                final String reply=api.streamChat(c,attachmentStore.hydrate(request),buffer);
                main.post(new Runnable(){public void run(){
                    if(destroyed||token!=generation)return;
                    finishLive();boolean saved=commitReply(text,reply);
                    pending="";pendingFiles=new JSONArray();busy(false);render();status.setText(saved?"Ready • latest 20 exchanges sent as context":"Could not save reply.");
                }});
            }catch(final Throwable e){main.post(new Runnable(){public void run(){
                if(destroyed||token!=generation)return;
                String partial=finishLive();boolean hasPartial=!partial.trim().isEmpty();
                boolean saved=hasPartial&&commitReply(text,partial+"\n[Reply interrupted]");
                pending="";pendingFiles=new JSONArray();input.setText(saved?"":text);busy(false);render();
                String message;
                if(e instanceof OutOfMemoryError)message="Not enough memory. Try fewer or smaller attachments.";
                else if(e instanceof javax.net.ssl.SSLException)message="Secure connection failed. Check the phone clock and TLS support.";
                else if(e instanceof java.net.SocketTimeoutException)message="Timed out. Check the network/server or try again.";
                else if(e instanceof java.net.ConnectException||e instanceof java.net.UnknownHostException)message="Cannot reach the server. Check URL, Wi-Fi, Ollama listener and firewall.";
                else {message=e.getMessage();if(message==null)message="Request failed.";if(!c.key.isEmpty())message=message.replace(c.key,"[redacted]");}
                status.setText((saved?"Partial reply saved. ":"")+message);
            }});}
        }},"chat-request").start();
    }
    private void stop(){
        generation++;if(active!=null)active.cancel();
        String partial=finishLive();boolean saved=!partial.trim().isEmpty()&&commitReply(pending,partial+"\n[Stopped]");
        input.setText(saved?"":pending);pending="";pendingFiles=new JSONArray();busy(false);render();
        status.setText(saved?"Stopped • partial reply saved. Server processing may continue.":"Stopped locally. Server processing may continue.");
    }
    private void saveDraftFiles(){prefs.edit().putString("draftFiles"+provider,attached.toString()).apply();}
    private void renderAttachments(){attachmentTray.removeAllViews();if(active==null)addPreviews(attachmentTray,attached,true);}
    private void addPreviews(LinearLayout parent,JSONArray files,final boolean editable){
        if(files==null)return;
        for(int i=0;i<files.length();i++){
            final JSONObject a=files.optJSONObject(i);if(a==null)continue;final int index=i;
            LinearLayout tile=column();tile.setPadding(dp(4),dp(4),dp(4),dp(4));
            if("image".equals(a.optString("kind"))){android.graphics.Bitmap bitmap=attachmentStore.thumbnail(a,editable?96:160);if(bitmap!=null){ImageView preview=new ImageView(this);preview.setImageBitmap(bitmap);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);tile.addView(preview,new LinearLayout.LayoutParams(editable?dp(96):-1,editable?dp(56):dp(150)));}}
            TextView name=label(a.optString("name"),12,ACCENT);name.setMaxLines(2);tile.addView(name);
            parent.addView(tile,new LinearLayout.LayoutParams(editable?dp(116):-1,-2));
            tile.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                if(editable){if(active!=null||importing)return;new AlertDialog.Builder(MainActivity.this).setTitle(a.optString("name")).setMessage("Remove this attachment?").setNegativeButton("Keep",null).setPositiveButton("Remove",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){JSONArray keep=new JSONArray();for(int j=0;j<attached.length();j++)if(j!=index)keep.put(attached.opt(j));attached=keep;saveDraftFiles();renderAttachments();pruneAttachments();}}).show();}
                else if("image".equals(a.optString("kind"))){ImageView large=new ImageView(MainActivity.this);large.setAdjustViewBounds(true);large.setImageBitmap(attachmentStore.thumbnail(a,768));new AlertDialog.Builder(MainActivity.this).setTitle(a.optString("name")).setView(large).setPositiveButton("Close",null).show();}
            }});
        }
    }
    private void chooseThinking(){
        final String[] levels=provider==0?new String[]{"default","on","off","low","medium","high"}:provider==3?new String[]{"default","low","high"}:new String[]{"default","low","medium","high"};
        new AlertDialog.Builder(this).setTitle("Thinking effort").setItems(levels,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){prefs.edit().putString("effort"+provider,levels[which]).apply();thinking.setText("Thinking: "+levels[which]);status.setText("Default uses model settings. Other levels require model/API support; higher effort can use more tokens.");}}).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=201||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();final int owner=provider;importing=true;busy(true);send.setText("Send");status.setText("Preparing attachment…");
        new Thread(new Runnable(){public void run(){try{
            final JSONObject added=attachmentStore.add(uri);
            main.post(new Runnable(){public void run(){if(destroyed)return;importing=false;if(provider==owner){attached.put(added);saveDraftFiles();renderAttachments();}busy(false);status.setText("Attached. Images need a vision model; PDFs need document support. Tap an attachment to remove it.");}});
        }catch(final Throwable error){main.post(new Runnable(){public void run(){if(destroyed)return;importing=false;busy(false);status.setText(error instanceof OutOfMemoryError?"Not enough memory for this image. Try a smaller image.":error.getMessage()==null?"Cannot open this attachment.":error.getMessage());}});}}},"attachment-import").start();
    }
    private void pruneAttachments(){
        Set<String> keep=new HashSet<String>();
        try{for(int p=0;p<Api.NAMES.length;p++){
            JSONArray saved=new JSONArray(prefs.getString("history"+p,"[]"));for(int i=0;i<saved.length();i++)collectFiles(saved.getJSONObject(i).optJSONArray("attachments"),keep);
            collectFiles(new JSONArray(prefs.getString("draftFiles"+p,"[]")),keep);
        }attachmentStore.prune(keep);}catch(Exception ignored){}
    }
    private void collectFiles(JSONArray files,Set<String> keep){if(files!=null)for(int i=0;i<files.length();i++){JSONObject a=files.optJSONObject(i);if(a!=null)keep.add(a.optString("id"));}}
    private EditText field(LinearLayout parent,String name,String value,boolean secret){
        parent.addView(label(name,12,MUTED));EditText e=new EditText(this);e.setTextSize(15);e.setSingleLine(true);e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);e.setText(value);parent.addView(e);return e;
    }
    private void showSettings(){
        final int p=provider;final Api.Config old=config();
        ScrollView wrapper=new ScrollView(this);LinearLayout form=column();form.setPadding(dp(16),dp(8),dp(16),dp(8));wrapper.addView(form);
        form.addView(label(p==0?"Use your PC's LAN address, not localhost. Phone and PC must share a trusted network.":p==3?"Use a Google AI Studio API key. Free-tier availability and quotas depend on the model and account.":p==4?"For services implementing OpenAI Chat Completions. HTTPS required.":"Requires a provider API key and API credit. A chat subscription is separate.",13,MUTED));
        final EditText base=field(form,"Base URL",old.base,false);
        final EditText key=field(form,p==0?"API key (optional for a proxy)":"API key",old.key,true);
        final CheckBox remember=new CheckBox(this);remember.setText("Remember key on this device");remember.setChecked(prefs.getBoolean("remember"+p,false));form.addView(remember);
        form.addView(label("Optional: saves the key in private app storage, without additional encryption. Backups are disabled. Otherwise the key lasts only for this app session.",11,MUTED));
        final EditText model=field(form,"Model ID",old.model,false);
        final Button fetch=button("Find models");form.addView(fetch);
        form.addView(label("Model lists can include non-chat models or paid models. Choose a text-chat model your account can use. Changing URL or model starts a new chat for this provider.",12,MUTED));
        final TextView feedback=label("",12,ACCENT);form.addView(feedback);
        final Api[] discovery={null};
        final AlertDialog dialog=new AlertDialog.Builder(this).setTitle(Api.NAMES[p]).setView(wrapper).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){if(discovery[0]!=null)discovery[0].cancel();}});
        fetch.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            final Api.Config c=new Api.Config(p,base.getText().toString(),key.getText().toString(),model.getText().toString());
            try{c.validate(false);}catch(Exception e){feedback.setText(e.getMessage());return;}
            final Api api=new Api();discovery[0]=api;fetch.setEnabled(false);feedback.setText("Loading models…");
            new Thread(new Runnable(){public void run(){try{
                final List<String> names=api.models(c);
                main.post(new Runnable(){public void run(){if(destroyed||!dialog.isShowing())return;fetch.setEnabled(true);feedback.setText(names.size()+" models found");
                    if(!names.isEmpty())new AlertDialog.Builder(MainActivity.this).setTitle("Choose a model").setItems(names.toArray(new String[names.size()]),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){model.setText(names.get(which));}}).show();
                }});
            }catch(final Exception e){main.post(new Runnable(){public void run(){if(destroyed||!dialog.isShowing())return;fetch.setEnabled(true);feedback.setText("Could not list models. Check connection/key or enter a model ID manually.");}});}}},"model-list").start();
        }});
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            Api.Config c=new Api.Config(p,base.getText().toString(),key.getText().toString(),model.getText().toString());
            try{c.validate(true);}catch(Exception e){feedback.setText(e.getMessage());return;}
            keys[p]=c.key;prefs.edit().putString("base"+p,c.base).putString("model"+p,c.model).putBoolean("remember"+p,remember.isChecked()).putString("key"+p,remember.isChecked()?c.key:"").apply();
            if(!old.base.equals(c.base)||!old.model.equals(c.model)){history=new JSONArray();saveHistory();pruneAttachments();}
            render();status.setText("Ready • "+c.model);dialog.dismiss();
        }});
    }
    @Override protected void onSaveInstanceState(Bundle state){
        drafts[provider]=pending.isEmpty()?input.getText().toString():pending;
        for(int i=0;i<drafts.length;i++)state.putString("draft"+i,drafts[i]);super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy(){destroyed=true;generation++;if(active!=null)active.cancel();if(live!=null)live.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
