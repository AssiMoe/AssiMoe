package de.mhv.sw09.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final String DEFAULT_URL="https://mhv-sw.de/mhv_overlay/control.php";
    private static final int FILE_PICK=7102;
    private static final int BG=Color.rgb(3,11,27), PANEL=Color.rgb(7,25,50), NAVY=Color.rgb(9,48,111);
    private static final int BLUE=Color.rgb(12,119,255), CYAN=Color.rgb(75,184,255), WHITE=Color.WHITE;
    private static final String[][] SCENES={
      {"matchday","MATCHDAY","◇","Spielankündigung"},
      {"startingSoon","STREAM BALD","◷","Stream startet bald"},
      {"startingSoonNow","STREAM GLEICH","▶","Stream startet gleich"},
      {"halftime","HALBZEIT","Ⅱ","Spielpause"},
      {"secondHalf","2. HALBZEIT","▶","Wiederanpfiff"},
      {"final","SPIELENDE","⚑","Abpfiff"},
      {"teams","MANNSCHAFTEN","♟","Teams vorstellen"},
      {"sponsors","SPONSOREN","✦","Unterstützer"},
      {"schedule","SPIELPLAN","▦","Kommende Spiele"},
      {"socials","SOCIALS","@","Social Media"},
      {"website","WEBSITE","⌘","mhv-sw.de"},
      {"about","ÜBER UNS","ℹ","Vereinsinfos"},
      {"contact","KONTAKT","✉","Kontaktinformationen"},
      {"technical","TECHNISCHE PROBLEME","⚒","Hinweisbild"},
      {"blackscreen","SCHWARZBILD","■","Bild ausblenden"}
    };
    private String server;
    private WebView web;
    private FrameLayout frame;
    private ScrollView dashboard;
    private TextView currentScene, status, onlineBadge;
    private LinearLayout root, bottom, header;
    private Button tabDashboard,tabWeb,tabServer;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnails=Executors.newFixedThreadPool(3);
    private final Map<String,Bitmap> imageCache=new ConcurrentHashMap<>();
    private final Map<String,LinearLayout> sceneCards=new ConcurrentHashMap<>();
    private boolean polling=false, requestBusy=false;
    private String tab="home";
    private int count=0;
    private final Runnable refreshTask=new Runnable(){@Override public void run(){if(polling){refreshRemoteState();handler.postDelayed(this,9000);}}};

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private GradientDrawable rounded(int color,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private GradientDrawable gradient(int first,int second,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{first,second});d.setCornerRadius(dp(radius));d.setStroke(dp(1),Color.argb(80,72,170,255));return d;}
    private TextView text(String str,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(str);v.setTextSize(sp);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void pad(View v,int l,int t,int r,int b){v.setPadding(dp(l),dp(t),dp(r),dp(b));}
    private void gap(LinearLayout v,int h){View a=new View(this);v.addView(a,lp(1,dp(h)));}

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);server=getPreferences(MODE_PRIVATE).getString("control_url",DEFAULT_URL);buildUi();showHome();}
    private void buildUi(){
      root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);setContentView(root);
      header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);pad(header,16,13,15,12);header.setBackground(gradient(Color.rgb(8,37,86),Color.rgb(4,18,41),0));root.addView(header,lp(-1,dp(82)));
      ImageView image=new ImageView(this);image.setImageResource(R.mipmap.ic_launcher);image.setScaleType(ImageView.ScaleType.FIT_CENTER);header.addView(image,lp(dp(54),dp(54)));
      LinearLayout branding=new LinearLayout(this);branding.setOrientation(1);pad(branding,11,0,0,0);header.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
      branding.addView(text("MHV BROADCAST V6",20,WHITE,true));TextView tagline=text("SCHWEINFURT 09  ·  STREAM CONTROL",9,Color.rgb(142,197,249),true);tagline.setLetterSpacing(.06f);branding.addView(tagline);
      TextView settings=text("⚙",26,CYAN,true);pad(settings,10,7,5,7);header.addView(settings);settings.setOnClickListener(v->openServerDialog());
      LinearLayout online=new LinearLayout(this);online.setGravity(Gravity.CENTER_VERTICAL);pad(online,14,8,14,8);online.setBackgroundColor(Color.rgb(5,20,42));root.addView(online,lp(-1,dp(38)));
      onlineBadge=text("●  Prüfe Verbindung …",12,Color.rgb(250,191,80),true);online.addView(onlineBadge,new LinearLayout.LayoutParams(0,-2,1));
      status=text("Regie bereit",11,Color.rgb(173,202,235),false);status.setGravity(Gravity.END);online.addView(status,new LinearLayout.LayoutParams(0,-2,1));
      frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
      dashboard=new ScrollView(this);dashboard.setFillViewport(true);dashboard.setVerticalScrollBarEnabled(false);frame.addView(dashboard,new FrameLayout.LayoutParams(-1,-1));
      LinearLayout sections=new LinearLayout(this);sections.setOrientation(1);pad(sections,14,16,14,25);dashboard.addView(sections);
      LinearLayout hero=new LinearLayout(this);hero.setOrientation(1);pad(hero,18,19,17,17);hero.setBackground(gradient(NAVY,Color.rgb(7,21,53),19));sections.addView(hero,lp(-1,-2));
      TextView small=text("●  MHV SCHWEINFURT 09  /  LIVE-REGIE",10,Color.rgb(117,213,255),true);small.setLetterSpacing(.09f);hero.addView(small);
      gap(hero,10);hero.addView(text("Dein Stream.\nDeine Regie.",34,WHITE,true));gap(hero,9);
      hero.addView(text("Alle Szenen und Einblendungen direkt vom Smartphone steuern.",13,Color.rgb(196,221,253),false));gap(hero,16);
      currentScene=text("Aktive Szene: Verbindung wird geprüft",14,Color.rgb(129,224,170),true);pad(currentScene,10,11,10,11);currentScene.setBackground(rounded(Color.rgb(4,25,62),Color.rgb(23,115,208),10));hero.addView(currentScene,lp(-1,-2));
      gap(sections,16);sections.addView(sectionHeading("SCHNELLSTEUERUNG","Direkte OBS-Schaltung"));
      LinearLayout quick=new LinearLayout(this);quick.setOrientation(0);sections.addView(quick,lp(-1,-2));
      quick.addView(quickButton("▶ SPIEL",BLUE,()->switchScene("live",null)),new LinearLayout.LayoutParams(0,dp(51),1));
      View g=new View(this);quick.addView(g,lp(dp(6),1));
      quick.addView(quickButton("Ⅱ HALBZEIT",Color.rgb(16,96,171),()->switchScene("scene","halftime")),new LinearLayout.LayoutParams(0,dp(51),1));
      View h=new View(this);quick.addView(h,lp(dp(6),1));
      quick.addView(quickButton("AUTO",Color.rgb(21,80,142),()->switchScene("auto",null)),new LinearLayout.LayoutParams(0,dp(51),1));
      gap(sections,20);sections.addView(sectionHeading("SZENENÜBERSICHT","Szenen direkt aktivieren"));
      GridLayout grid=new GridLayout(this);grid.setColumnCount(2);grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);grid.setUseDefaultMargins(false);sections.addView(grid,lp(-1,-2));
      int width=getResources().getDisplayMetrics().widthPixels-dp(28)-dp(8);
      int each=width/2;
      for(int i=0;i<SCENES.length;i++){final String[] scene=SCENES[i];
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(1);pad(tile,13,13,10,11);
        int first=scene[0].equals("halftime")?Color.rgb(120,75,6):scene[0].equals("technical")?Color.rgb(106,28,44):scene[0].equals("blackscreen")?Color.rgb(12,15,25):Color.rgb(11,52,103);
        tile.setBackground(gradient(first,Color.rgb(7,22,49),14));tile.setElevation(dp(1));
        ImageView preview=new ImageView(this);
        preview.setBackground(rounded(Color.rgb(8,32,67),0,7));
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(preview,lp(-1,dp(92)));
        loadSceneImage(scene[0],preview);
        gap(tile,8);
        TextView title=text(scene[1],12,WHITE,true);title.setMaxLines(2);tile.addView(title);
        TextView sub=text(scene[3],10,Color.rgb(173,200,233),false);sub.setMaxLines(1);tile.addView(sub);
        sceneCards.put(scene[0],tile);
        GridLayout.LayoutParams slot=new GridLayout.LayoutParams();slot.width=each-dp(7);slot.height=dp(162);slot.rowSpec=GridLayout.spec(i/2);slot.columnSpec=GridLayout.spec(i%2);slot.setMargins(dp(3),dp(3),dp(3),dp(5));grid.addView(tile,slot);
        tile.setOnClickListener(v->switchScene("scene",scene[0]));
      }
      gap(sections,17);
      Button open=quickButton("▣  GESAMTES WEBCONTROL ÖFFNEN",BLUE,()->showWeb());sections.addView(open,lp(-1,dp(56)));
      gap(sections,13);TextView footer=text("V6: Grafikvorschau, Live-Regie, Szenenwechsel. Erweiterte Einstellungen im Webcontrol.",12,Color.rgb(135,167,208),false);sections.addView(footer);
      web=new WebView(this);frame.addView(web,new FrameLayout.LayoutParams(-1,-1));web.setVisibility(View.GONE);
      WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setAllowFileAccess(false);ws.setAllowContentAccess(false);ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);ws.setSupportMultipleWindows(false);CookieManager.getInstance().setAcceptCookie(true);
      web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){status.setText("Webcontrol verbunden");}@Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest r,android.webkit.WebResourceError e){if(r.isForMainFrame())status.setText("Webcontrol nicht erreichbar");}});
      web.setWebChromeClient(new WebChromeClient(){@Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;try{startActivityForResult(params.createIntent(),FILE_PICK);return true;}catch(ActivityNotFoundException e){fileCallback=null;callback.onReceiveValue(null);return false;}}});
      bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER);pad(bottom,9,7,9,9);bottom.setBackgroundColor(Color.rgb(6,21,45));root.addView(bottom,lp(-1,dp(66)));
      tabDashboard=bottomButton("◈  REGIE",()->showHome());tabWeb=bottomButton("▣  WEBCONTROL",()->showWeb());tabServer=bottomButton("⚙  SERVER",this::openServerDialog);
      bottom.addView(tabDashboard,new LinearLayout.LayoutParams(0,dp(48),1));bottom.addView(tabWeb,new LinearLayout.LayoutParams(0,dp(48),1));bottom.addView(tabServer,new LinearLayout.LayoutParams(0,dp(48),1));
      web.loadUrl(normalizedBase()+"control.php");
    }
    private void highlightScene(String selected){
      for(Map.Entry<String,LinearLayout> entry:sceneCards.entrySet()){
        boolean active=entry.getKey().equals(selected);
        int start=active?Color.rgb(8,104,195):Color.rgb(11,52,103);
        entry.getValue().setBackground(gradient(start,Color.rgb(7,22,49),14));
        entry.getValue().setElevation(dp(active?7:1));
      }
    }
    private void loadSceneImage(String scene,ImageView view){
      Bitmap cached=imageCache.get(scene);
      if(cached!=null){view.setImageBitmap(cached);return;}
      if(scene.equals("blackscreen")){view.setBackgroundColor(Color.BLACK);return;}
      final String url=normalizedBase()+"assets/scenes/"+scene+".webp";
      thumbnails.execute(()->{
        HttpURLConnection conn=null;
        try{
          conn=(HttpURLConnection)new URL(url).openConnection();
          conn.setConnectTimeout(7500);conn.setReadTimeout(7500);
          if(conn.getResponseCode()!=200)return;
          BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=4;
          Bitmap image;
          try(InputStream input=conn.getInputStream()){image=BitmapFactory.decodeStream(input,null,opts);}
          if(image!=null){imageCache.put(scene,image);runOnUiThread(()->view.setImageBitmap(image));}
        }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
      });
    }
    private LinearLayout sectionHeading(String kicker,String heading){LinearLayout l=new LinearLayout(this);l.setOrientation(1);TextView k=text(kicker,10,CYAN,true);k.setLetterSpacing(.12f);l.addView(k);gap(l,4);l.addView(text(heading,20,WHITE,true));gap(l,11);return l;}
    private Button quickButton(String text,int color,Runnable run){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(gradient(color,Color.rgb(8,47,97),11));b.setOnClickListener(v->run.run());return b;}
    private Button bottomButton(String title,Runnable run){Button b=new Button(this);b.setText(title);b.setTextSize(10);b.setAllCaps(false);b.setTextColor(Color.rgb(179,206,240));b.setBackground(rounded(Color.TRANSPARENT,0,9));b.setOnClickListener(v->run.run());return b;}
    private void showHome(){tab="home";if(dashboard!=null)dashboard.setVisibility(View.VISIBLE);if(web!=null)web.setVisibility(View.GONE);markTabs();}
    private void showWeb(){tab="web";dashboard.setVisibility(View.GONE);web.setVisibility(View.VISIBLE);markTabs();}
    private void markTabs(){if(tabDashboard!=null){tabDashboard.setBackground(rounded(tab.equals("home")?NAVY:Color.TRANSPARENT,0,10));tabWeb.setBackground(rounded(tab.equals("web")?NAVY:Color.TRANSPARENT,0,10));}}
    private String normalizedBase(){String s=server.trim();if(s.endsWith("/"))return s;int i=s.lastIndexOf('/');if(s.matches("(?i).*\\.php(?:\\?.*)?$"))return s.substring(0,i+1);return s+"/";}
    private void openServerDialog(){EditText input=new EditText(this);input.setSingleLine(true);input.setText(server);input.setSelectAllOnFocus(true);LinearLayout box=new LinearLayout(this);pad(box,22,10,22,8);box.addView(input);new AlertDialog.Builder(this).setTitle("MHV Webhost-Adresse").setMessage("HTTPS-Adresse des MHV-Overlays (z.B. https://mhv-sw.de/mhv_overlay/control.php)").setView(box).setPositiveButton("Speichern",(d,w)->{String url=input.getText().toString().trim();try{Uri u=Uri.parse(url);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getHost().trim().isEmpty())throw new IllegalArgumentException();server=url;getPreferences(MODE_PRIVATE).edit().putString("control_url",server).apply();imageCache.clear();for(String[] sc:SCENES){ /* reload on next app launch */ }web.loadUrl(normalizedBase()+"control.php");refreshRemoteState();Toast.makeText(this,"Webhost übernommen",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Ungültige HTTPS-Adresse",Toast.LENGTH_LONG).show();}}).setNeutralButton("OBS-Link kopieren",(d,w)->{((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OBS",normalizedBase()+"overlay.php"));Toast.makeText(this,"OBS-Link kopiert",Toast.LENGTH_SHORT).show();}).setNegativeButton("Abbrechen",null).show();}
    private String nameFor(String key){if(key==null)return "Automatisch";if(key.equals("live"))return "SPIEL ANZEIGEN";if(key.equals("auto"))return "AUTOMATIK";if(key.equals("standby"))return "STANDBY";if(key.equals("hidden"))return "AUSGEBLENDET";for(String[] s:SCENES)if(s[0].equals(key))return s[1];return key;}
    private void switchScene(String mode,String scene){if(requestBusy)return;requestBusy=true;status.setText("Schalte Szene …");new Thread(()->{HttpURLConnection c=null;try{JSONObject j=new JSONObject();j.put("overlayMode",mode);if(scene!=null)j.put("manualScene",scene);c=(HttpURLConnection)new URL(normalizedBase()+"backend.php?action=config").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(10000);c.setReadTimeout(12000);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream os=c.getOutputStream()){os.write(j.toString().getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();String body=readResponse(c,code);JSONObject r=new JSONObject(body);if(code<200||code>=300||!r.optBoolean("ok",false))throw new Exception(r.optString("error","HTTP "+code));runOnUiThread(()->{String value=nameFor(scene==null?mode:scene);currentScene.setText("AKTIV: "+value);highlightScene(scene);status.setText("Szene übernommen");onlineBadge.setText("●  Webhost verbunden");onlineBadge.setTextColor(Color.rgb(82,234,153));web.reload();});}catch(Exception e){runOnUiThread(()->{status.setText("Schalten fehlgeschlagen");Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();});}finally{if(c!=null)c.disconnect();requestBusy=false;}}).start();}
    private String readResponse(HttpURLConnection c,int code)throws Exception{InputStream in=code>=400?c.getErrorStream():c.getInputStream();if(in==null)throw new Exception("Keine Antwort vom Server");StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)b.append(line);}return b.toString();}
    private void refreshRemoteState(){if(requestBusy)return;new Thread(()->{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(normalizedBase()+"backend.php?action=config").openConnection();c.setConnectTimeout(7000);c.setReadTimeout(8000);c.setRequestProperty("Accept","application/json");int code=c.getResponseCode();JSONObject result=new JSONObject(readResponse(c,code));if(code!=200||!result.optBoolean("ok",false))throw new Exception("Serverfehler "+code);JSONObject cfg=result.getJSONObject("config");String mode=cfg.optString("overlayMode","auto");String scene=mode.equals("scene")?cfg.optString("manualScene","matchday"):mode;runOnUiThread(()->{onlineBadge.setText("●  Webhost verbunden");onlineBadge.setTextColor(Color.rgb(80,228,156));currentScene.setText("AKTIV: "+nameFor(scene));highlightScene(scene);status.setText("Live-Synchronisierung");});}catch(Exception e){runOnUiThread(()->{onlineBadge.setText("●  Verbindung prüfen");onlineBadge.setTextColor(Color.rgb(255,188,99));status.setText("Offline / Server prüfen");});}finally{if(c!=null)c.disconnect();}}).start();}
    @Override protected void onResume(){super.onResume();polling=true;handler.removeCallbacks(refreshTask);handler.post(refreshTask);}
    @Override protected void onPause(){polling=false;handler.removeCallbacks(refreshTask);super.onPause();}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==FILE_PICK&&fileCallback!=null){fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;}}
    @Override public void onBackPressed(){if(tab.equals("web")){if(web.canGoBack())web.goBack();else showHome();}else super.onBackPressed();}
    @Override protected void onDestroy(){polling=false;handler.removeCallbacks(refreshTask);if(web!=null)web.destroy();thumbnails.shutdownNow();super.onDestroy();}
}