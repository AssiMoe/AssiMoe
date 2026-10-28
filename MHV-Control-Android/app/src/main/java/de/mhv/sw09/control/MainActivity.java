package de.mhv.sw09.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final String DEFAULT_URL="https://mhv-sw.de/mhv_overlay/control.php";
  private static final int BG=Color.rgb(4,12,29), CARD=Color.rgb(9,28,59), BLUE=Color.rgb(14,116,248);
  private static final int BORDER=Color.rgb(37,72,119), WHITE=Color.WHITE, MUTED=Color.rgb(161,193,231);
  private static final String[][] SCENES={
    {"matchday","MATCHDAY"},{"startingSoon","STREAM BALD"},{"startingSoonNow","STREAM GLEICH"},{"halftime","HALBZEIT"},
    {"secondHalf","2. HALBZEIT"},{"final","SPIELENDE"},{"teams","MANNSCHAFTEN"},{"sponsors","SPONSOREN"},
    {"schedule","SPIELPLAN"},{"socials","SOCIAL MEDIA"},{"website","WEBSITE"},{"about","ÜBER UNS"},
    {"contact","KONTAKT"},{"technical","TECHNISCHE PROBLEME"},{"blackscreen","SCHWARZBILD"}
  };
  private String server, activeScene="auto", tab="home";
  private TextView currentScene, status;
  private WebView browser;
  private FrameLayout pages;
  private ScrollView dashboard;
  private LinearLayout bottom;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService workers=Executors.newFixedThreadPool(3);
  private final Map<String,ImageView> previews=new LinkedHashMap<>();
  private final Map<String,LinearLayout> cards=new LinkedHashMap<>();
  private boolean polling=false, switching=false;
  private ValueCallback<Uri[]> fileCallback;
  private static final int PICK=6823;
  private final Runnable poll=new Runnable(){public void run(){if(polling){if(!switching && "home".equals(tab))refreshState();handler.postDelayed(this,12000);}}};

  private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
  private GradientDrawable rounded(int color,int radius,int stroke){
    GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;
  }
  private GradientDrawable grad(int c1,int c2,int radius){
    GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{c1,c2});
    g.setCornerRadius(dp(radius));g.setStroke(dp(1),BORDER);return g;
  }
  private TextView text(String s,int sp,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(sp);
    if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
  }
  private void pad(View v,int a,int b,int c,int d){v.setPadding(dp(a),dp(b),dp(c),dp(d));}
  private void space(LinearLayout v,int h){View x=new View(this);v.addView(x,lp(1,dp(h)));}
  private Button button(String label,int background,Runnable click){
    Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    b.setTextColor(WHITE);b.setBackground(rounded(background,11,BORDER));b.setOnClickListener(v->click.run());return b;
  }
  private String base(){
    String s=server.trim();if(s.endsWith("/"))return s;int index=s.lastIndexOf('/');
    return s.matches("(?i).*\\.php(?:\\?.*)?$")?s.substring(0,index+1):s+"/";
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
    server=getPreferences(MODE_PRIVATE).getString("server",DEFAULT_URL);buildUi();showHome();
  }
  private void buildUi(){
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);setContentView(root);
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setBackground(grad(Color.rgb(7,32,74),Color.rgb(5,18,44),0));pad(header,15,11,13,11);
    root.addView(header,lp(-1,dp(77)));
    ImageView logo=new ImageView(this);logo.setImageResource(R.mipmap.ic_launcher);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    header.addView(logo,lp(dp(52),dp(52)));
    LinearLayout texts=new LinearLayout(this);texts.setOrientation(1);pad(texts,10,0,0,0);
    header.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
    texts.addView(text("MHV BROADCAST",20,WHITE,true));
    TextView sub=text("SCHWEINFURT 09  ·  LIVE-REGIE",10,MUTED,true);sub.setLetterSpacing(.09f);texts.addView(sub);
    Button gear=button("⚙",Color.rgb(14,59,117),this::serverDialog);header.addView(gear,lp(dp(51),dp(44)));

    LinearLayout info=new LinearLayout(this);info.setGravity(Gravity.CENTER_VERTICAL);info.setBackgroundColor(Color.rgb(4,20,46));pad(info,15,7,15,7);
    root.addView(info,lp(-1,dp(36)));
    status=text("●  Verbindung wird geprüft",11,Color.rgb(255,194,100),true);info.addView(status);
    pages=new FrameLayout(this);root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
    dashboard=new ScrollView(this);dashboard.setFillViewport(true);dashboard.setVerticalScrollBarEnabled(false);
    pages.addView(dashboard,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout body=new LinearLayout(this);body.setOrientation(1);pad(body,12,12,12,22);dashboard.addView(body);

    LinearLayout hero=new LinearLayout(this);hero.setOrientation(1);hero.setBackground(grad(Color.rgb(9,48,106),Color.rgb(5,20,48),18));pad(hero,18,18,18,17);
    body.addView(hero,lp(-1,-2));
    TextView eyebrow=text("●  STREAM CONTROL  /  V7",10,Color.rgb(115,204,255),true);eyebrow.setLetterSpacing(.1f);hero.addView(eyebrow);
    space(hero,11);hero.addView(text("Dein Spiel.\nDeine Regie.",33,WHITE,true));
    space(hero,9);hero.addView(text("OBS-Szenen direkt schalten. Alle Änderungen werden mit dem Webhost synchronisiert.",12,MUTED,false));
    space(hero,14);
    currentScene=text("AKTIVE AUSGABE  ·  Lädt …",13,Color.rgb(107,235,180),true);
    pad(currentScene,12,13,12,13);currentScene.setBackground(rounded(Color.rgb(4,24,56),12,BORDER));hero.addView(currentScene,lp(-1,-2));

    space(body,15);body.addView(section("SCHNELLSTEUERUNG","Direkt auf Sendung"));
    HorizontalScrollView bar=new HorizontalScrollView(this);bar.setHorizontalScrollBarEnabled(false);body.addView(bar);
    LinearLayout actions=new LinearLayout(this);bar.addView(actions);
    fast(actions,"▶ SPIEL",BLUE,()->setScene("live",null));
    fast(actions,"Ⅱ HALBZEIT",Color.rgb(13,87,173),()->setScene("scene","halftime"));
    fast(actions,"↻ AUTO",Color.rgb(9,73,131),()->setScene("auto",null));
    fast(actions,"■ SCHWARZ",Color.rgb(28,39,61),()->setScene("scene","blackscreen"));

    space(body,20);body.addView(section("SZENENBIBLIOTHEK","Deine Szenen"));
    TextView hint=text("Tippe auf eine Szene, um sie unmittelbar in OBS zu aktivieren.",11,MUTED,false);body.addView(hint);
    space(body,11);
    int item=0;
    while(item<SCENES.length){
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.TOP);body.addView(row,lp(-1,-2));
      for(int n=0;n<2;n++,item++){
        if(item>=SCENES.length)break;
        final String id=SCENES[item][0],title=SCENES[item][1];
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(1);tile.setBackground(grad(Color.rgb(10,39,78),Color.rgb(6,19,43),12));
        pad(tile,7,7,7,9);
        LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,dp(153),1);slot.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(tile,slot);
        ImageView picture=new ImageView(this);picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setBackground(rounded(Color.rgb(5,18,38),8,0));tile.addView(picture,lp(-1,dp(94)));
        TextView label=text(title,11,WHITE,true);label.setMaxLines(2);pad(label,4,9,1,0);tile.addView(label,lp(-1,dp(35)));
        tile.setOnClickListener(v->setScene("scene",id));cards.put(id,tile);previews.put(id,picture);loadPreview(id,picture);
      }
      space(body,5);
    }
    space(body,12);
    TextView help=text("WEBHOST + OBS",11,Color.rgb(102,194,255),true);body.addView(help);
    space(body,7);
    Button control=button("WEB-CONTROL ÖFFNEN",Color.rgb(8,67,137),this::showWeb);body.addView(control,lp(-1,dp(46)));
    space(body,8);
    Button obs=button("OBS-LINK KOPIEREN",Color.rgb(13,35,67),this::copyObsLink);body.addView(obs,lp(-1,dp(43)));

    browser=new WebView(this);browser.setVisibility(View.GONE);pages.addView(browser,new FrameLayout.LayoutParams(-1,-1));
    WebSettings web=browser.getSettings();web.setJavaScriptEnabled(true);web.setDomStorageEnabled(true);
    web.setAllowFileAccess(false);web.setAllowContentAccess(false);
    web.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    CookieManager.getInstance().setAcceptCookie(true);
    browser.setWebViewClient(new WebViewClient(){
      @Override public void onPageFinished(WebView view,String url){if(tab.equals("web"))status.setText("●  Webcontrol geöffnet");}
      @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest req,android.webkit.WebResourceError error){
        if(req.isForMainFrame())status.setText("●  Webhost nicht erreichbar");
      }
    });
    browser.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
        if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;
        try{startActivityForResult(params.createIntent(),PICK);return true;}
        catch(ActivityNotFoundException e){fileCallback=null;callback.onReceiveValue(null);return false;}
      }
    });
    browser.loadUrl(base()+"control.php");

    bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER);bottom.setBackgroundColor(Color.rgb(6,22,48));pad(bottom,8,7,8,8);
    root.addView(bottom,lp(-1,dp(62)));
    Button home=button("⌂  REGIE",Color.rgb(9,67,135),this::showHome);
    Button webButton=button("▣  WEBCONTROL",Color.rgb(7,34,68),this::showWeb);
    Button settings=button("⚙  SERVER",Color.rgb(7,34,68),this::serverDialog);
    bottom.addView(home,new LinearLayout.LayoutParams(0,dp(45),1));
    LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(0,dp(45),1);margin.setMargins(dp(5),0,dp(5),0);
    bottom.addView(webButton,margin);bottom.addView(settings,new LinearLayout.LayoutParams(0,dp(45),1));
  }
  private void fast(LinearLayout row,String label,int color,Runnable action){
    Button b=button(label,color,action);LinearLayout.LayoutParams params=lp(-2,dp(47));params.setMargins(dp(2),0,dp(5),0);row.addView(b,params);
  }
  private LinearLayout section(String eyebrow,String heading){
    LinearLayout x=new LinearLayout(this);x.setOrientation(1);TextView a=text(eyebrow,10,Color.rgb(83,186,255),true);a.setLetterSpacing(.12f);
    x.addView(a);space(x,5);x.addView(text(heading,20,WHITE,true));space(x,10);return x;
  }
  private void showHome(){tab="home";dashboard.setVisibility(View.VISIBLE);browser.setVisibility(View.GONE);refreshState();}
  private void showWeb(){tab="web";dashboard.setVisibility(View.GONE);browser.setVisibility(View.VISIBLE);}
  private void serverDialog(){
    EditText field=new EditText(this);field.setSingleLine(true);field.setText(server);field.setSelectAllOnFocus(true);
    LinearLayout wrapper=new LinearLayout(this);pad(wrapper,24,10,24,0);wrapper.addView(field);
    new AlertDialog.Builder(this).setTitle("MHV-Webhost-Adresse").setMessage("HTTPS-Adresse zur Control-Seite").setView(wrapper)
      .setPositiveButton("Speichern",(d,w)->{
        String candidate=field.getText().toString().trim();Uri url=Uri.parse(candidate);
        if(!"https".equalsIgnoreCase(url.getScheme())||url.getHost()==null){Toast.makeText(this,"Gültige HTTPS-Adresse erforderlich",Toast.LENGTH_LONG).show();return;}
        server=candidate;getPreferences(MODE_PRIVATE).edit().putString("server",server).apply();
        browser.loadUrl(base()+"control.php");for(Map.Entry<String,ImageView> x:previews.entrySet())loadPreview(x.getKey(),x.getValue());refreshState();
      }).setNeutralButton("OBS-Link kopieren",(d,w)->copyObsLink()).setNegativeButton("Abbrechen",null).show();
  }
  private void copyObsLink(){
    ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OBS-URL",base()+"overlay.php"));
    Toast.makeText(this,"OBS-Link kopiert",Toast.LENGTH_SHORT).show();
  }
  private String sceneName(String id){
    if(id.equals("live"))return "SPIEL";if(id.equals("auto"))return "AUTOMATIK";if(id.equals("hidden"))return "AUSGEBLENDET";
    for(String[] x:SCENES)if(x[0].equals(id))return x[1];return id.toUpperCase();
  }
  private void select(String scene){
    activeScene=scene;
    currentScene.setText("AKTIVE AUSGABE  ·  "+sceneName(scene));
    for(Map.Entry<String,LinearLayout> entry:cards.entrySet()){
      boolean selected=entry.getKey().equals(scene);
      entry.getValue().setBackground(grad(selected?Color.rgb(13,100,196):Color.rgb(10,39,78),Color.rgb(6,19,43),12));
      entry.getValue().setElevation(dp(selected?6:0));
    }
  }
  private static String response(HttpURLConnection c,int code)throws Exception{
    InputStream input=code>=400?c.getErrorStream():c.getInputStream();
    if(input==null)throw new Exception("Keine Serverantwort");
    StringBuilder s=new StringBuilder();try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
      String row;while((row=reader.readLine())!=null)s.append(row);
    }return s.toString();
  }
  private void setScene(String mode,String name){
    if(switching)return;switching=true;status.setText("●  Schalte Szene …");
    workers.execute(()->{
      HttpURLConnection c=null;
      try{
        JSONObject payload=new JSONObject();payload.put("overlayMode",mode);if(name!=null)payload.put("manualScene",name);
        c=(HttpURLConnection)new URL(base()+"backend.php?action=config").openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(9000);c.setReadTimeout(11000);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();JSONObject result=new JSONObject(response(c,code));
        if(code<200||code>=300||!result.optBoolean("ok",false))throw new Exception(result.optString("error","HTTP "+code));
        JSONObject cfg=result.optJSONObject("config");String key=(cfg!=null && "scene".equals(cfg.optString("overlayMode")))?cfg.optString("manualScene",name):mode;
        runOnUiThread(()->{select(key);status.setText("●  Szene übernommen");});
      }catch(Exception e){runOnUiThread(()->{status.setText("●  Schalten fehlgeschlagen");Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();});}
      finally{if(c!=null)c.disconnect();switching=false;}
    });
  }
  private void refreshState(){
    if(switching)return;
    workers.execute(()->{
      HttpURLConnection c=null;
      try{
        c=(HttpURLConnection)new URL(base()+"backend.php?action=config").openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(8000);
        int code=c.getResponseCode();JSONObject result=new JSONObject(response(c,code));
        if(code!=200||!result.optBoolean("ok",false))throw new Exception("HTTP "+code);
        JSONObject cfg=result.getJSONObject("config");String mode=cfg.optString("overlayMode","auto");
        String chosen=mode.equals("scene")?cfg.optString("manualScene","matchday"):mode;
        runOnUiThread(()->{select(chosen);status.setText("●  Webhost verbunden");status.setTextColor(Color.rgb(106,236,171));});
      }catch(Exception e){runOnUiThread(()->{status.setText("●  Verbindung prüfen");status.setTextColor(Color.rgb(255,196,105));});}
      finally{if(c!=null)c.disconnect();}
    });
  }
  private void loadPreview(String id,ImageView view){
    if(id.equals("blackscreen")){view.setBackgroundColor(Color.BLACK);return;}
    workers.execute(()->{
      HttpURLConnection c=null;
      try{
        c=(HttpURLConnection)new URL(base()+"assets/scenes/"+id+".webp").openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(9000);
        if(c.getResponseCode()!=200)return;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=4;
        Bitmap b;try(InputStream stream=c.getInputStream()){b=BitmapFactory.decodeStream(stream,null,options);}
        if(b!=null)runOnUiThread(()->view.setImageBitmap(b));
      }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
    });
  }
  @Override protected void onResume(){super.onResume();polling=true;handler.removeCallbacks(poll);handler.post(poll);}
  @Override protected void onPause(){polling=false;handler.removeCallbacks(poll);super.onPause();}
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);if(request==PICK && fileCallback!=null){
      fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;
    }
  }
  @Override public void onBackPressed(){if(tab.equals("web")){if(browser.canGoBack())browser.goBack();else showHome();}else super.onBackPressed();}
  @Override protected void onDestroy(){polling=false;handler.removeCallbacks(poll);if(browser!=null)browser.destroy();workers.shutdownNow();super.onDestroy();}
}
