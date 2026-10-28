package de.mhv.sw09.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.net.Uri;
import android.graphics.Color;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.Toast;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
  private static final int FILE_PICK = 7124;
  private static final String DEFAULT_URL = "https://mhv-sw.de/mhv_overlay/control.php";
  private static final int BLUE = Color.rgb(11,47,107);
  private WebView web;
  private TextView status;
  private String server;
  private ValueCallback<Uri[]> fileCallback;

  private Button action(LinearLayout row, String label, Runnable task) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(Color.WHITE);
    button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2,-2);
    lp.setMargins(3,2,3,2);
    row.addView(button,lp);
    button.setOnClickListener(v -> task.run());
    return button;
  }
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(5,22,52));
    getWindow().setNavigationBarColor(Color.rgb(5,22,52));
    server = getPreferences(MODE_PRIVATE).getString("server",DEFAULT_URL);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(Color.rgb(244,247,252));
    setContentView(root);
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(12,4,8,4);
    bar.setBackgroundColor(BLUE);
    root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
    TextView title = new TextView(this);
    title.setText("MHV 09 · CONTROL");
    title.setTextSize(19);
    title.setTextColor(Color.WHITE);
    title.setTypeface(null,1);
    bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button settings = action(bar,"Server",this::serverDialog);
    settings.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(49,129,213)));
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout actions = new LinearLayout(this);
    actions.setPadding(5,3,5,3);
    scroll.addView(actions);
    action(actions,"▶ Spiel",()->setScene("live",null));
    action(actions,"⏸ Halbzeit",()->setScene("scene","halftime"));
    action(actions,"Stream gleich",()->setScene("scene","startingSoonNow"));
    action(actions,"Matchday",()->setScene("scene","matchday"));
    action(actions,"Blackscreen",()->setScene("scene","blackscreen"));
    action(actions,"Auto",()->setScene("auto",null));
    action(actions,"⟳",this::loadPanel);
    status = new TextView(this);
    status.setText("Bereit");
    status.setTextColor(Color.rgb(37,56,84));
    status.setPadding(13,2,13,5);
    root.addView(status,new LinearLayout.LayoutParams(-1,-2));
    web = new WebView(this);
    root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
    WebSettings ws = web.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setDomStorageEnabled(true);
    ws.setAllowFileAccess(false);
    ws.setAllowContentAccess(false);
    ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    CookieManager.getInstance().setAcceptCookie(true);
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v,String url) { status.setText("Control-Panel verbunden"); }
      @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest req,android.webkit.WebResourceError err) {
        if(req.isForMainFrame())status.setText("Webhost nicht erreichbar – Server prüfen");
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> cb,FileChooserParams params) {
        if(fileCallback!=null)fileCallback.onReceiveValue(null);
        fileCallback=cb;
        try{startActivityForResult(params.createIntent(),FILE_PICK);return true;}
        catch(ActivityNotFoundException e){fileCallback=null;cb.onReceiveValue(null);return false;}
      }
    });
    loadPanel();
  }
  private String base() {
    String s=server.trim();
    if(s.endsWith("/"))return s;
    int pos=s.lastIndexOf('/');
    if(s.matches("(?i).*\\.php(?:\\?.*)?$"))return s.substring(0,pos+1);
    return s+"/";
  }
  private void loadPanel() {
    if(web!=null)web.loadUrl(base()+"control.php");
    if(status!=null)status.setText("Lade Control-Panel …");
  }
  private void serverDialog() {
    EditText input=new EditText(this);
    input.setSingleLine(true);
    input.setText(server);
    LinearLayout wrap=new LinearLayout(this);
    wrap.setPadding(25,5,25,5);
    wrap.addView(input);
    new AlertDialog.Builder(this).setTitle("MHV Webhost Control-URL").setView(wrap)
      .setPositiveButton("Speichern",(d,w)->{
        String s=input.getText().toString().trim();
        if(!s.startsWith("https://")){Toast.makeText(this,"HTTPS-URL erforderlich",Toast.LENGTH_LONG).show();return;}
        server=s;
        getPreferences(MODE_PRIVATE).edit().putString("server",s).apply();
        loadPanel();
      }).setNegativeButton("Abbrechen",null).show();
  }
  private void setScene(String mode,String scene) {
    final String endpoint=base()+"backend.php?action=config";
    status.setText("Szene wird geschaltet …");
    new Thread(()->{
      HttpURLConnection c=null;
      try {
        JSONObject request=new JSONObject();
        request.put("overlayMode",mode);
        if(scene!=null)request.put("manualScene",scene);
        c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try(OutputStream out=c.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
        int response=c.getResponseCode();
        InputStream stream=response>=400?c.getErrorStream():c.getInputStream();
        StringBuilder body=new StringBuilder();
        if(stream!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
          String line;
          while((line=reader.readLine())!=null)body.append(line);
        }
        JSONObject reply=new JSONObject(body.toString());
        if(response<200||response>=300||!reply.optBoolean("ok",false))
          throw new Exception(reply.optString("error","HTTP "+response));
        runOnUiThread(()->{status.setText("Aktiv: "+(scene==null?mode:scene));web.reload();});
      }catch(Exception error){
        runOnUiThread(()->{status.setText("Fehler: "+error.getMessage());Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();});
      }finally{if(c!=null)c.disconnect();}
    }).start();
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==FILE_PICK&&fileCallback!=null){
      fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));
      fileCallback=null;
    }
  }
  @Override public void onBackPressed() {
    if(web!=null&&web.canGoBack())web.goBack();
    else super.onBackPressed();
  }
  @Override protected void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}
